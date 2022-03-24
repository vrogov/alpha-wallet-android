package com.alphawallet.app.service;

import android.net.Uri;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.LongSparseArray;

import com.alphawallet.app.C;
import com.alphawallet.app.entity.ContractType;
import com.alphawallet.app.entity.nftassets.NFTAsset;
import com.alphawallet.app.entity.opensea.AssetContract;
import com.alphawallet.app.entity.tokens.Token;
import com.alphawallet.app.entity.tokens.TokenFactory;
import com.alphawallet.app.entity.tokens.TokenInfo;
import com.alphawallet.ethereum.EthereumNetworkBase;
import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.Single;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;
import timber.log.Timber;

/**
 * Created by James on 2/10/2018.
 * Stormbird in Singapore
 */

public class OpenSeaService
{
    private static final String EMPTY_RESULT = "{\"noresult\":[]}";
    private static OkHttpClient httpClient;
    private static final int PAGE_SIZE = 50;
    private final Map<String, String> imageUrls = new HashMap<>();
    private static final TokenFactory tf = new TokenFactory();
    private final LongSparseArray<Long> networkCheckTimes = new LongSparseArray<>();
    private final LongSparseArray<Integer> pageOffsets = new LongSparseArray<>();

    static
    {
        System.loadLibrary("keys");
    }

    public static native String getOpenSeaKey();

    public OpenSeaService()
    {
        pageOffsets.clear();
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    private static Request buildRequest(String api)
    {
        Request.Builder requestB = new Request.Builder()
                .url(api)
                .header("User-Agent", "Chrome/74.0.3729.169")
                .method("GET", null)
                .addHeader("Content-Type", "application/json");

        String apiKey = getOpenSeaKey();
        if (!TextUtils.isEmpty(apiKey) && !apiKey.equals("..."))
        {
            requestB.addHeader("X-API-KEY", apiKey);
        }

        return requestB.build();
    }

    private static String executeRequest(String api)
    {
        try (okhttp3.Response response = httpClient.newCall(buildRequest(api)).execute())
        {
            ResponseBody responseBody = response.body();
            if (responseBody != null)
            {
                return responseBody.string();
            }
        }
        catch (Exception e)
        {
            Timber.e(e);
        }

        return EMPTY_RESULT;
    }

    public Single<Token[]> getTokens(String address,
                                     long networkId,
                                     String networkName,
                                     TokensService tokensService)
    {
        return Single.fromCallable(() ->
        {
            int receivedTokens;
            int currentPage = 0;
            Map<String, Token> foundTokens = new HashMap<>();

            long currentTime = System.currentTimeMillis();
            if (!canCheckChain(networkId)) return new Token[0];
            networkCheckTimes.put(networkId, currentTime);

            int pageOffset = pageOffsets.get(networkId, 0);

            Timber.d("Fetch from opensea : %s", networkName);

            do
            {
                String jsonData = fetchAssets(networkId, address, pageOffset);
                if (!hasAssets(jsonData))
                {
                    return foundTokens.values().toArray(new Token[0]); //on error return results found so far
                }

                JSONObject result = new JSONObject(jsonData);
                JSONArray assets;
                if (result.has("assets"))
                {
                    assets = result.getJSONArray("assets");
                }
                else
                {
                    assets = result.getJSONArray("results");
                }

                receivedTokens = assets.length();
                pageOffset += assets.length();

                //process this page of results
                processOpenseaTokens(foundTokens, assets, address, networkId, networkName, tokensService);
                currentPage++;
            }
            while (receivedTokens == PAGE_SIZE && currentPage <= 3); //fetch 4 pages for each loop

            if (receivedTokens < PAGE_SIZE)
            {
                Timber.d("Reset OpenSeaAPI reads at: %s", pageOffset);
                pageOffsets.put(networkId, 0);
            }
            else
            {
                pageOffsets.put(networkId, pageOffset);
                networkCheckTimes.put(networkId, currentTime - 55 * DateUtils.SECOND_IN_MILLIS); //do another read within 5 seconds
            }

            //now write the contract images
            for (Map.Entry<String, String> entry : imageUrls.entrySet())
            {
                tokensService.addTokenImageUrl(networkId, entry.getKey(), entry.getValue());
            }
            imageUrls.clear();

            return foundTokens.values().toArray(new Token[0]);
        });
    }

    private void processOpenseaTokens(Map<String, Token> foundTokens,
                                      JSONArray assets,
                                      String address,
                                      long networkId,
                                      String networkName,
                                      TokensService tokensService) throws Exception
    {
        final Map<String, Map<BigInteger, NFTAsset>> assetList = new HashMap<>();

        for (int i = 0; i < assets.length(); i++)
        {
            JSONObject assetJSON = assets.getJSONObject(i);
            AssetContract assetContract =
                    new Gson().fromJson(assetJSON.getString("asset_contract"), AssetContract.class);

            if (assetContract != null && !TextUtils.isEmpty(assetContract.getSchemaName()))
            {
                switch (assetContract.getSchemaName())
                {
                    case "ERC721":
                        handleERC721(assetContract, assetList, assetJSON, networkId, foundTokens, tokensService,
                                networkName, address);
                        break;
                    case "ERC1155":
                        handleERC1155(assetContract, assetList, assetJSON, networkId, foundTokens, tokensService,
                                networkName, address);
                        break;
                }
            }
        }
    }

    private void handleERC721(AssetContract assetContract,
                              Map<String, Map<BigInteger, NFTAsset>> assetList,
                              JSONObject assetJSON,
                              long networkId,
                              Map<String, Token> foundTokens,
                              TokensService svs,
                              String networkName,
                              String address) throws Exception
    {
        NFTAsset asset = new NFTAsset(assetJSON.toString());

        BigInteger tokenId = assetJSON.has("token_id") ?
                new BigInteger(assetJSON.getString("token_id"))
                : null;
        if (tokenId == null) return;

        addAssetImageToHashMap(assetContract.getAddress(), assetContract.getImageUrl());

        Token token = foundTokens.get(assetContract.getAddress());
        if (token == null)
        {
            TokenInfo tInfo;
            ContractType type;
            long lastCheckTime = 0;
            Token checkToken = svs.getToken(networkId, assetContract.getAddress());
            if (checkToken != null && (checkToken.isERC721() || checkToken.isERC721Ticket()))
            {
                assetList.put(assetContract.getAddress(), checkToken.getTokenAssets());
                tInfo = checkToken.tokenInfo;
                type = checkToken.getInterfaceSpec();
                lastCheckTime = checkToken.lastTxTime;
            }
            else //if we haven't seen the contract before, or it was previously logged as something other than a ERC721 variant then specify undetermined flag
            {
                tInfo = new TokenInfo(assetContract.getAddress(), assetContract.getName(), assetContract.getSymbol(), 0, true, networkId);
                type = ContractType.ERC721_UNDETERMINED;
            }

            token = tf.createToken(tInfo, type, networkName);
            token.setTokenWallet(address);
            token.lastTxTime = lastCheckTime;
            foundTokens.put(assetContract.getAddress(), token);
        }
        asset.updateAsset(tokenId, assetList.get(token.getAddress()));
        token.addAssetToTokenBalanceAssets(tokenId, asset);
    }

    private void handleERC1155(AssetContract assetContract,
                               Map<String, Map<BigInteger, NFTAsset>> assetList,
                               JSONObject assetJSON,
                               long networkId,
                               Map<String, Token> foundTokens,
                               TokensService svs,
                               String networkName,
                               String address) throws Exception
    {
        NFTAsset asset = new NFTAsset(assetJSON.toString());

        BigInteger tokenId = assetJSON.has("token_id") ?
                new BigInteger(assetJSON.getString("token_id"))
                : null;

        if (tokenId == null) return;

        addAssetImageToHashMap(assetContract.getAddress(), assetContract.getImageUrl());

        Token token = foundTokens.get(assetContract.getAddress());
        if (token == null)
        {
            TokenInfo tInfo;
            ContractType type;
            long lastCheckTime = 0;
            Token checkToken = svs.getToken(networkId, assetContract.getAddress());
            if (checkToken != null && checkToken.getInterfaceSpec() == ContractType.ERC1155)
            {
                assetList.put(assetContract.getAddress(), checkToken.getTokenAssets());
                tInfo = checkToken.tokenInfo;
                type = checkToken.getInterfaceSpec();
                lastCheckTime = checkToken.lastTxTime;
            }
            else
            {
                tInfo = new TokenInfo(assetContract.getAddress(), assetContract.getName(), assetContract.getSymbol(), 0, true, networkId);
                type = ContractType.ERC1155;
            }

            token = tf.createToken(tInfo, type, networkName);
            token.setTokenWallet(address);
            token.lastTxTime = lastCheckTime;
            token.setAssetContract(assetContract);
            foundTokens.put(assetContract.getAddress(), token);
        }
        asset.updateAsset(tokenId, assetList.get(token.getAddress()));
        token.addAssetToTokenBalanceAssets(tokenId, asset);
    }

    private void addAssetImageToHashMap(String address, String imageUrl)
    {
        if (!imageUrls.containsKey(address) && !TextUtils.isEmpty(imageUrl))
        {
            imageUrls.put(address, imageUrl);
        }
    }

    private boolean hasAssets(String jsonData)
    {
        return jsonData != null && jsonData.length() >= 10 && jsonData.contains("assets"); //validate return from API
    }

    public void resetOffsetRead(List<Long> networkFilter)
    {
        long offsetTime = System.currentTimeMillis() - 57 * DateUtils.SECOND_IN_MILLIS;
        for (long networkId : networkFilter)
        {
            if (com.alphawallet.app.repository.EthereumNetworkBase.hasOpenseaAPI(networkId))
            {
                networkCheckTimes.put(networkId, offsetTime);
                offsetTime += 10 * DateUtils.SECOND_IN_MILLIS;
            }
        }
        pageOffsets.clear();
    }

    public boolean canCheckChain(long networkId)
    {
        long lastCheckTime = networkCheckTimes.get(networkId, 0L);
        return System.currentTimeMillis() > (lastCheckTime + DateUtils.MINUTE_IN_MILLIS);
    }

    public Single<String> getAsset(Token token, BigInteger tokenId)
    {
        return Single.fromCallable(() ->
                fetchAsset(token.tokenInfo.chainId, token.tokenInfo.address, tokenId.toString()));
    }

    public static String fetchAssets(long networkId, String address, int offset)
    {
        String api = "";
        if (networkId == EthereumNetworkBase.MAINNET_ID)
        {
            api = C.OPENSEA_ASSETS_API_MAINNET + address;
        }
        else if (networkId == EthereumNetworkBase.RINKEBY_ID)
        {
            api = C.OPENSEA_ASSETS_API_RINKEBY + address;
        }
        else if (networkId == EthereumNetworkBase.MATIC_ID)
        {
            api = C.OPENSEA_ASSETS_API_MATIC + address;
        }

        Uri.Builder builder = new Uri.Builder();
        builder.encodedPath(api)
                .appendQueryParameter("limit", String.valueOf(PAGE_SIZE))
                .appendQueryParameter("offset", String.valueOf(offset));

        return executeRequest(builder.build().toString());
    }

    public static String fetchAsset(long networkId, String contractAddress, String tokenId)
    {
        String api = "";
        if (networkId == EthereumNetworkBase.MAINNET_ID)
        {
            api = C.OPENSEA_SINGLE_ASSET_API_MAINNET + contractAddress + "/" + tokenId;
        }
        else if (networkId == EthereumNetworkBase.RINKEBY_ID)
        {
            api = C.OPENSEA_SINGLE_ASSET_API_RINKEBY + contractAddress + "/" + tokenId;
        }
        else if (networkId == EthereumNetworkBase.MATIC_ID)
        {
            api = C.OPENSEA_SINGLE_ASSET_API_MATIC + contractAddress + "/" + tokenId;
        }

        return executeRequest(api);
    }
}
