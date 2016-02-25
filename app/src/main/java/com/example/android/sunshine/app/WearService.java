package com.example.android.sunshine.app;

import android.app.IntentService;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.util.Log;

import com.bumptech.glide.util.Util;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;

/**
 * Created by mmhan on 9/2/16.
 */
public class WearService extends IntentService implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, Loader.OnLoadCompleteListener<Cursor> {


    private static final String DATA_PATH = "/weather";
    private static final String DATA_WEATHER_ID = "id";
    private static final String DATA_WEATHER_MIN = "weather_min";
    private static final String DATA_WEATHER_MAX = "weather_max";
    private static final String DATA_WEATHER_ICON = "weather_icon";
    private final String LOG_TAG = WearService.class.getName();

    private GoogleApiClient apiClient;

    public WearService() {
        super("WearService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        connectApiClient();
    }

    public void connectApiClient(){
        apiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
        apiClient.connect();
    }

    public void getData(){
        String sortOrder = WeatherContract.WeatherEntry.COLUMN_DATE + " ASC";

        String locationSetting = Utility.getPreferredLocation(getApplicationContext());
        Uri weatherForLocationUri = WeatherContract.WeatherEntry.buildWeatherLocationWithStartDate(
                locationSetting, System.currentTimeMillis());

        CursorLoader loader = new CursorLoader(getApplicationContext(),
                weatherForLocationUri,
                ForecastFragment.FORECAST_COLUMNS,
                null,
                null,
                sortOrder);
        loader.registerListener(0, this);
        loader.startLoading();
    }

    @Override
    public void onConnected(Bundle bundle) {
        Log.e(LOG_TAG, "onConnected");
        getData();
    }

    @Override
    public void onConnectionSuspended(int i) {
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.e(LOG_TAG, "onConnectionFailed " + connectionResult.getErrorMessage());
    }

    @Override
    public void onLoadComplete(Loader<Cursor> loader, Cursor data) {
        Log.e(LOG_TAG, "onLoadComplete");
        data.moveToFirst();
        sendData(data);
    }

    public void sendData(Cursor data){
        final int weatherId = data.getInt(ForecastFragment.COL_WEATHER_CONDITION_ID);
        double high = data.getDouble(ForecastFragment.COL_WEATHER_MAX_TEMP);
        final String highString = Utility.formatTemperature(getApplicationContext(), high);
       
        // Read low temperature from cursor
        double low = data.getDouble(ForecastFragment.COL_WEATHER_MIN_TEMP);
        final String lowString = Utility.formatTemperature(getApplicationContext(), low);

        final String icon_url = Utility.getArtUrlForWeatherCondition(getApplicationContext(),weatherId);

        new Thread(new Runnable() {
            @Override
            public void run() {
                PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(DATA_PATH);
                putDataMapRequest.getDataMap().putLong("Time", System.currentTimeMillis());
                putDataMapRequest.getDataMap().putInt(DATA_WEATHER_ID, weatherId);
                putDataMapRequest.getDataMap().putString(DATA_WEATHER_MIN, lowString);
                putDataMapRequest.getDataMap().putString(DATA_WEATHER_MAX,highString);

                Bitmap bitmap = BitmapFactory.decodeResource(getResources(), Utility.getArtResourceForWeatherCondition(weatherId));
                Asset asset = createAssetFromBitmap(bitmap);
                putDataMapRequest.getDataMap().putAsset(DATA_WEATHER_ICON,asset);

                PutDataRequest dataRequest = putDataMapRequest.asPutDataRequest().setUrgent();

                PendingResult<DataApi.DataItemResult> pendingResult = Wearable.DataApi
                        .putDataItem(apiClient, dataRequest);

                pendingResult.setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                    @Override
                    public void onResult(DataApi.DataItemResult dataItemResult) {
                        Log.e(LOG_TAG, "Data sent");
                    }
                });
            }
        }).start();
    }
    private static Asset createAssetFromBitmap(Bitmap bitmap) {
        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
        return Asset.createFromBytes(byteStream.toByteArray());
    }
}
