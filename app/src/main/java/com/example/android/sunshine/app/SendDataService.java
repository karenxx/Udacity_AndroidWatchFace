package com.example.android.sunshine.app;

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

public class SendDataService extends IntentService {
    public SendDataService() {
        super("GCMService");
    }

    public final String LOG_TAG =SendDataService.class.getSimpleName();
    private static GoogleApiClient mGoogleApiClient;
    public static final String WEATHER_PATH = "/weather";
    public static final String KEY_HIGH = "high";
    public static final String KEY_LOW = "low";
    public static final String KEY_WEATHER_ID = "weatherId";

    String high;
    String low;
    int weatherId;

    @Override
    protected void onHandleIntent(Intent intent) {
        Bundle extras = intent.getExtras();
        high = Utility.formatTemperature(this, extras.getDouble("high"));
        low = Utility.formatTemperature(this, extras.getDouble("low"));
        weatherId = extras.getInt("weatherId");

        Log.d(LOG_TAG, "low " + low + "high  " + high + "id " + weatherId);

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(Bundle bundle) {
                        sendData(high, low, weatherId);
                    }

                    @Override
                    public void onConnectionSuspended(int i) {
                        Log.w(LOG_TAG, "connection is suspended " + i);
                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(ConnectionResult connectionResult) {
                        Log.w(LOG_TAG, "Fail to connect " + connectionResult);
                    }
                })
                .build();
                mGoogleApiClient.connect();
    }

    private void sendData(String high, String low, int weatherId) {
        Log.d(LOG_TAG, "send data");
        PutDataMapRequest weatherDataReq = PutDataMapRequest.create(WEATHER_PATH);
        DataMap weatherDataMap = weatherDataReq.getDataMap();
        weatherDataMap.putString(KEY_HIGH, high);
        weatherDataMap.putString(KEY_LOW, low);
        weatherDataMap.putInt(KEY_WEATHER_ID, weatherId);
        PutDataRequest req = weatherDataReq.asPutDataRequest();
        Wearable.DataApi.putDataItem(mGoogleApiClient, req)
                .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                    @Override
                    public void onResult(DataApi.DataItemResult dataItemResult) {
                        if (!dataItemResult.getStatus().isSuccess()) {
                            Log.e(LOG_TAG, "fail to get data " + dataItemResult);
                        } else {
                            Log.i(LOG_TAG, "succeed to get data");
                        }
                    }
                });
    }

}
