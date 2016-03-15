/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

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
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);


    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private static final int MSG_UPDATE_TEMP = 1;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                    case MSG_UPDATE_TEMP:
                        engine.handleUpdateTempMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine
        implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener {

        GoogleApiClient apiClient;
        DataMap mWeatherData;

        public static final String WEATHER_DATA_PATH = "/weather";

        private static final String DATA_WEATHER_MIN = "weather_min";
        private static final String DATA_WEATHER_MAX = "weather_max";
        private static final String DATA_WEATHER_ICON = "weather_icon";

        final String LOG_TAG = Engine.class.getName();
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mTempTextPaint;
        Paint mDateTextPaint;
        Bitmap weatherIcon;
        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;

        float mTempXOffset;
        float mTempYOffset;

        float mDateXOffset;
        float mDateYOffset;

        float mLineYOffset;
        float mIconYOffset;

        InputStream assetInputStream;


        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mTempYOffset = resources.getDimension(R.dimen.digital_temp_y_offset);
            mDateYOffset = resources.getDimension(R.dimen.date_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mTempTextPaint = new Paint();
            mTempTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mDateTextPaint = new Paint();
            mDateTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTime = new Time();

            apiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();

                apiClient.connect();
            } else {
                unregisterReceiver();

                if(apiClient != null && apiClient.isConnected()) {
                    Wearable.DataApi.removeListener(apiClient, this);
                    apiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            mTempXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_temp_x_offset_round : R.dimen.digital_temp_x_offset);
            mDateXOffset = resources.getDimension(isRound? R.dimen.date_x_offset_round:R.dimen.date_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mLineYOffset = resources.getDimension(isRound? R.dimen.line_y_offset_round:R.dimen.line_y_offset);
            mIconYOffset = resources.getDimension(isRound? R.dimen.icon_y_offset_round:R.dimen.icon_y_offset);
            mTextPaint.setTextSize(textSize);

            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_temp_text_size_round : R.dimen.digital_temp_text_size);
            mTempTextPaint.setTextSize(tempTextSize);

            float dateTextSize = resources.getDimension(isRound? R.dimen.date_text_size_round:R.dimen.date_text_size);
            mDateTextPaint.setTextSize(dateTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mTempTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            String text = mAmbient
                    ? String.format("%d:%02d", mTime.hour, mTime.minute)
                    : String.format("%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);
            canvas.drawText(text, mXOffset, mYOffset, mTextPaint);
            canvas.drawText(DateFormat.getDateInstance(DateFormat.FULL).format(System.currentTimeMillis()),mDateXOffset,mDateYOffset,mDateTextPaint);
            canvas.drawLine(50,mLineYOffset,300,mLineYOffset,mTempTextPaint);
            if(mWeatherData != null) {
                canvas.drawText(
                        String.format(
                                "%s / %s",
                                mWeatherData.getString(DATA_WEATHER_MAX),
                                mWeatherData.getString(DATA_WEATHER_MIN))
                        , mTempXOffset, mTempYOffset, mTempTextPaint);

                canvas.drawBitmap(weatherIcon, 50, mIconYOffset, null);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
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
            for (DataEvent event : dataEventBuffer){
                if(event.getType() == DataEvent.TYPE_CHANGED){
                    Log.e(LOG_TAG,"onTypeChanged");
                    DataItem item = event.getDataItem();
                    if(item.getUri().getPath().compareTo(WEATHER_DATA_PATH) == 0){
                        mWeatherData = DataMapItem.fromDataItem(item).getDataMap();
                        Asset imgAsset = mWeatherData.getAsset(DATA_WEATHER_ICON);
                        loadBitmapFromAsset(imgAsset);
                        //showData();
                        Log.e(LOG_TAG, "Weather Min " + mWeatherData.getString(DATA_WEATHER_MIN));
                        Log.e(LOG_TAG, "Weather Max " + mWeatherData.getString(DATA_WEATHER_MAX));
                        Log.e(LOG_TAG, "Time " + String.valueOf(mWeatherData.getLong("Time")));

                        //Asset profileAsset = dataMap.getAsset(DATA_WEATHER_ICON);
                        //loadBitmapFromAsset(profileAsset, weather_icon);
                    }
                }
            }
        }
        public void loadBitmapFromAsset(Asset asset) {
            Wearable.DataApi.getFdForAsset(apiClient,asset).setResultCallback(new ResultCallback<DataApi.GetFdForAssetResult>() {
                @Override
                public void onResult(DataApi.GetFdForAssetResult getFdForAssetResult) {
                    assetInputStream = getFdForAssetResult.getInputStream();
                    weatherIcon = BitmapFactory.decodeStream(assetInputStream);
                    Bitmap newIcon = Bitmap.createScaledBitmap(weatherIcon,weatherIcon.getWidth()/2,weatherIcon.getHeight()/2,true);
                    weatherIcon = newIcon;
                }
            });
        }

        public void handleUpdateTempMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
