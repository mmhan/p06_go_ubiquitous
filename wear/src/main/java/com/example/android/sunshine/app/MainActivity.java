package com.example.android.sunshine.app;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.android.sunshine.app.wear.R;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.text.DateFormat;

public class MainActivity extends Activity implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {
    public static final String WEATHER_DATA_PATH = "/weather";
    public static final String WEATHER_ID = "id";

    private static final String DATA_WEATHER_MIN = "weather_min";
    private static final String DATA_WEATHER_MAX = "weather_max";
    private static final String DATA_WEATHER_ICON = "weather_icon";
    private final String LOG_TAG = MainActivity.class.getName();
    TextView current_time,current_date,weather_min,weather_max,error_message;
    LinearLayout main_layout;
    ImageView weather_icon;
    GoogleApiClient apiClient;
    InputStream assetInputStream;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        current_time = (TextView) findViewById(R.id.current_time);
        current_date = (TextView) findViewById(R.id.current_date);
        weather_min = (TextView) findViewById(R.id.weather_min_textview);
        weather_max = (TextView) findViewById(R.id.weather_max_textview);
        error_message = (TextView) findViewById(R.id.error_message);
        main_layout = (LinearLayout) findViewById(R.id.main_layout);
        weather_icon = (ImageView) findViewById(R.id.weather_icon);


        apiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
        showError();

    }

    @Override
    protected void onResume() {
        super.onResume();
        apiClient.connect();
    }

    @Override
    public void onConnected(Bundle bundle) {
        Log.e(LOG_TAG, "onConnected");
        Wearable.DataApi.addListener(apiClient, this);
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.e(LOG_TAG, "onConnectionSuspended");
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.e(LOG_TAG,"onConnectionFailed");
        Log.e(LOG_TAG, connectionResult.toString());
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
        Log.e(LOG_TAG,"onDataChanged");
        for (DataEvent event : dataEventBuffer){
            if(event.getType() == DataEvent.TYPE_CHANGED){
                Log.e(LOG_TAG,"onTypeChanged");
                DataItem item = event.getDataItem();
                if(item.getUri().getPath().compareTo(WEATHER_DATA_PATH) == 0){
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    showData();
                    Log.e(LOG_TAG, "Weather ID " + String.valueOf(dataMap.getInt(WEATHER_ID)));
                    Log.e(LOG_TAG, "Weather Min " + dataMap.getString(DATA_WEATHER_MIN));
                    Log.e(LOG_TAG, "Weather Max " + dataMap.getString(DATA_WEATHER_MAX));
                    Log.e(LOG_TAG, "Time " + String.valueOf(dataMap.getLong("Time")));
                    current_time.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(dataMap.getLong("Time")));
                    current_date.setText(DateFormat.getDateInstance(DateFormat.FULL).format(dataMap.getLong("Time")));
                    weather_min.setText(dataMap.getString(DATA_WEATHER_MIN));
                    weather_max.setText(dataMap.getString(DATA_WEATHER_MAX));

                    Asset profileAsset = dataMap.getAsset(DATA_WEATHER_ICON);
                    loadBitmapFromAsset(profileAsset, weather_icon);
                }
            }
        }
    }
    public void loadBitmapFromAsset(Asset asset, final ImageView weather_icon) {
        Wearable.DataApi.getFdForAsset(apiClient,asset).setResultCallback(new ResultCallback<DataApi.GetFdForAssetResult>() {
            @Override
            public void onResult(DataApi.GetFdForAssetResult getFdForAssetResult) {
                assetInputStream = getFdForAssetResult.getInputStream();
                weather_icon.setImageBitmap(BitmapFactory.decodeStream(assetInputStream));
            }
        });
    }

    private void showData(){
        main_layout.setVisibility(View.VISIBLE);
        error_message.setVisibility(View.GONE);
    }
    private void showError(){
        main_layout.setVisibility(View.GONE);
        error_message.setVisibility(View.VISIBLE);
    }
}
