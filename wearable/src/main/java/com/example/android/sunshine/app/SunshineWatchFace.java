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
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final String LOG_TAG = SunshineWatchFace.class.getSimpleName();
    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);


    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;


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
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        private static final String WEATHER_PATH = "/weather";
        private static final String KEY_HIGH = "high";
        private static final String KEY_LOW = "low";
        private static final String KEY_WEATHER_ID = "weatherId";

        Calendar mCalendar;
        SimpleDateFormat mDayOfWeekFormat;
        java.text.DateFormat mDateFormat;
        GoogleApiClient mGoogleApiClient;

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mTimeAmbientPaint;
        Paint mDatePaint;
        Paint mDateAmbientPaint;
        Paint mHiPaint;
        Paint mHiAmbientPaint;
        Paint mLowPaint;
        Paint mLowAmbientPaint;

        boolean mAmbient;

        Bitmap mWeatherIcon;
        String mHigh;
        String mLow;

        float mTimeYOffset;
        float mDateYOffset;
        float mDividerYOffset;
        float mWeatherYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };

        private void initFormats() {
            mDayOfWeekFormat = new SimpleDateFormat("EEEE", Locale.getDefault());
            mDayOfWeekFormat.setCalendar(mCalendar);
            mDateFormat = DateFormat.getDateFormat(SunshineWatchFace.this);
            mDateFormat.setCalendar(mCalendar);
        }


        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = SunshineWatchFace.this.getResources();
            mTimeYOffset = resources.getDimension(R.dimen.time_y_offset);
            mDateYOffset = resources.getDimension(R.dimen.date_y_offset);
            mDividerYOffset = resources.getDimension(R.dimen.divider_y_offset);
            mWeatherYOffset = resources.getDimension(R.dimen.weather_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary));
            mTimePaint = createTextPaint(getResources().getColor(R.color.white), BOLD_TYPEFACE);
            mTimeAmbientPaint = createTextPaint(getResources().getColor(R.color.white), NORMAL_TYPEFACE);
            mDatePaint = createTextPaint(getResources().getColor(R.color.secondary_text_light));
            mDateAmbientPaint = createTextPaint(getResources().getColor(R.color.white));
            mLowPaint = createTextPaint(getResources().getColor(R.color.secondary_text_light));
            mLowAmbientPaint = createTextPaint(getResources().getColor(R.color.white));
            mHiPaint = createTextPaint(getResources().getColor(R.color.white), BOLD_TYPEFACE);
            mHiAmbientPaint = createTextPaint(getResources().getColor(R.color.white), NORMAL_TYPEFACE);

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int defaultInteractiveColor) {
            return createTextPaint(defaultInteractiveColor, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int defaultInteractiveColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(defaultInteractiveColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        //this is called to provide information about every special display support
        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        //onTimeTick is called in ambient mode every minute
        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        //get called whenever a switch is made between modes
        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mHiPaint.setAntiAlias(!inAmbientMode);
                    mLowPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }


        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();

            float timeTextSize = resources.getDimension(isRound ?
                    R.dimen.time_text_size_round : R.dimen.time_text_size);
            mTimePaint.setTextSize(timeTextSize);
            mTimeAmbientPaint.setTextSize(timeTextSize);

            float dateTextSize = resources.getDimension(isRound ?
                    R.dimen.date_text_size_round : R.dimen.date_text_size);
            mDatePaint.setTextSize(dateTextSize);
            mDateAmbientPaint.setTextSize(dateTextSize);

            float tempTextSize = resources.getDimension(isRound ?
                    R.dimen.temp_text_size_round : R.dimen.temp_text_size);
            mHiPaint.setTextSize(tempTextSize);
            mLowPaint.setTextSize(tempTextSize);
            mHiAmbientPaint.setTextSize(tempTextSize);
            mLowAmbientPaint.setTextSize(tempTextSize);
        }


        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mBackgroundPaint);
            }

            mCalendar.setTimeInMillis(System.currentTimeMillis());
            boolean format24Hour = DateFormat.is24HourFormat(SunshineWatchFace.this);
            int minute = mCalendar.get(Calendar.MINUTE);
            int amPm = mCalendar.get(Calendar.AM_PM);

            String timeString;
            if (format24Hour) {
                int hour = mCalendar.get(Calendar.HOUR_OF_DAY);
                timeString = String.format("%02d:%02d", hour, minute);
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) hour = 12;

                String amPMString = amPm == Calendar.AM ? "am" : "pm";
                timeString = String.format("%d:%02d %s", hour, minute, amPMString);
            }

            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.US);
            String dateString = dateFormat.format(mCalendar.getTime()).toUpperCase(Locale.US);

            canvas.drawLine(bounds.centerX() - 20, mDividerYOffset, bounds.centerX() + 20, mDividerYOffset, mDateAmbientPaint);

            if (mLow != null && mHigh != null) {
                if (isInAmbientMode()) {
                    float xOffsetTime = mTimeAmbientPaint.measureText(timeString) / 2;
                    canvas.drawText(timeString, bounds.centerX() - xOffsetTime, mTimeYOffset, mTimeAmbientPaint);

                    float xOffsetDate = mDateAmbientPaint.measureText(dateString) / 2;
                    canvas.drawText(dateString, bounds.centerX() - xOffsetDate, mDateYOffset, mDateAmbientPaint);

                    float highWidth = mHiAmbientPaint.measureText(mHigh);
                    float lowWidth = mLowAmbientPaint.measureText(mLow);
                    float xOffsetHigh = bounds.centerX() - ((highWidth + lowWidth) / 2);
                    float xOffsetLow = xOffsetHigh + highWidth;
                    canvas.drawText(mHigh, xOffsetHigh, mWeatherYOffset, mHiAmbientPaint);
                    canvas.drawText(mLow, xOffsetLow, mWeatherYOffset, mLowAmbientPaint);
                } else {
                    float xOffsetTime = mTimePaint.measureText(timeString) / 2;
                    canvas.drawText(timeString, bounds.centerX() - xOffsetTime, mTimeYOffset, mTimePaint);

                    float xOffsetDate = mDatePaint.measureText(dateString) / 2;
                    canvas.drawText(dateString, bounds.centerX() - xOffsetDate, mDateYOffset, mDatePaint);

                    float highWidth = mHiPaint.measureText(mHigh);
                    float lowWidth = mLowPaint.measureText(mLow);
                    float iconWidth = mHiPaint.getTextSize() / mWeatherIcon.getHeight() * mWeatherIcon.getWidth();
                    mWeatherIcon = Bitmap.createScaledBitmap(
                            mWeatherIcon, (int)iconWidth, (int)mHiPaint.getTextSize(), true);
                    float xOffsetWeather = bounds.centerX() - ((highWidth + lowWidth + 10 + iconWidth) / 2);
                    float xOffsetHigh = xOffsetWeather + 10 + iconWidth;
                    float xOffsetLow = xOffsetHigh + highWidth;
                    canvas.drawBitmap(mWeatherIcon, xOffsetWeather, mWeatherYOffset - mWeatherIcon.getHeight(), null);
                    canvas.drawText(mHigh, xOffsetHigh, mWeatherYOffset, mHiPaint);
                    canvas.drawText(mLow, xOffsetLow, mWeatherYOffset, mLowPaint);
                }
            }

        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                mCalendar.setTimeZone(TimeZone.getDefault());
                mCalendar.setTimeInMillis(System.currentTimeMillis());
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
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
            Log.d(LOG_TAG, "onConnected");
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onConnectionSuspended: " + i);
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(LOG_TAG, "onDataChanged");
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = dataEvent.getDataItem();
                    if (dataItem.getUri().getPath().compareTo(WEATHER_PATH) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                        syncWeatherData(dataMap.getString(KEY_HIGH),
                                dataMap.getString(KEY_LOW), dataMap.getInt(KEY_WEATHER_ID));
                        invalidate();
                    }
                }
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onConnectionFailed: " + connectionResult);
            }
        }

        private void syncWeatherData(String high, String low, int weatherId) {
            this.mHigh = high;
            this.mLow = low;
            int iconId = getIconResourceForWeatherCondition(weatherId);
            mWeatherIcon = BitmapFactory.decodeResource(
                    SunshineWatchFace.this.getResources(), iconId);

        }

        public int getIconResourceForWeatherCondition(int weatherId) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.art_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.art_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.art_rain;
            } else if (weatherId == 511) {
                return R.drawable.art_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.art_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.art_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.art_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                return R.drawable.art_storm;
            } else if (weatherId == 800) {
                return R.drawable.art_clear;
            } else if (weatherId == 801) {
                return R.drawable.art_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.art_clouds;
            }
            return -1;
        }
    }
}
