package de.michelinside.glucodatahandler

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.util.Log
import de.michelinside.glucodatahandler.common.*
import de.michelinside.glucodatahandler.common.notification.ChannelType
import de.michelinside.glucodatahandler.common.notification.Channels
import de.michelinside.glucodatahandler.common.R as CR
import de.michelinside.glucodatahandler.common.notifier.*
import de.michelinside.glucodatahandler.common.utils.PackageUtils


class GlucoDataServiceWear: GlucoDataService(AppSource.WEAR_APP), NotifierInterface {
    private val LOG_ID = "GDH.GlucoDataServiceWear"
    init {
        Log.d(LOG_ID, "init called")
        InternalNotifier.addNotifier( this,
            ActiveComplicationHandler, mutableSetOf(
                NotifySource.MESSAGECLIENT,
                NotifySource.BROADCAST,
                NotifySource.SETTINGS,
                NotifySource.TIME_VALUE
            )
        )
        InternalNotifier.addNotifier( this,
            BatteryLevelComplicationUpdater,
            mutableSetOf(
                NotifySource.CAPILITY_INFO,
                NotifySource.BATTERY_LEVEL,
                NotifySource.NODE_BATTERY_LEVEL
            )
        )
    }

    companion object {
        fun start(context: Context) {
            start(AppSource.WEAR_APP, context, GlucoDataServiceWear::class.java)
        }
    }

    override fun onCreate() {
        try {
            Log.d(LOG_ID, "onCreate called")
            super.onCreate()
            migrateSettings()
            AlarmNotificationWear.initNotifications(this)
            val filter = mutableSetOf(
                NotifySource.BROADCAST,
                NotifySource.MESSAGECLIENT,
                NotifySource.OBSOLETE_VALUE) // to trigger re-start for the case of stopped by the system
            InternalNotifier.addNotifier(this, this, filter)
            ActiveComplicationHandler.OnNotifyData(this, NotifySource.CAPILITY_INFO, null)
        } catch (ex: Exception) {
            Log.e(LOG_ID, "onCreate exception: " + ex)
        }
    }

    private fun migrateSettings() {
        try {
            Log.v(LOG_ID, "migrateSettings called")
            val sharedPref = this.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
            // notification to vibrate_only
            if(!sharedPref.contains(Constants.SHARED_PREF_NOTIFICATION_VIBRATE) && sharedPref.contains("notification")) {
                with(sharedPref.edit()) {
                    putBoolean(Constants.SHARED_PREF_NOTIFICATION_VIBRATE, sharedPref.getBoolean("notification", false))
                    apply()
                }
            }

            // complications
            if(!sharedPref.contains(Constants.SHARED_PREF_COMPLICATION_TAP_ACTION)) {
                val curApp = if(PackageUtils.isPackageAvailable(this, Constants.PACKAGE_JUGGLUCO)) Constants.PACKAGE_JUGGLUCO else this.packageName
                Log.i(LOG_ID, "Setting default tap action for complications to $curApp")
                with(GlucoDataService.sharedPref!!.edit()) {
                    putString(Constants.SHARED_PREF_COMPLICATION_TAP_ACTION, curApp)
                    apply()
                }
            }

        } catch( exc: Exception ) {
            Log.e(LOG_ID, exc.message + "\n" + exc.stackTraceToString())
        }
    }

    override fun OnNotifyData(context: Context, dataSource: NotifySource, extras: Bundle?) {
        try {
            Log.d(LOG_ID, "OnNotifyData called for source " + dataSource.toString())
            start(context)
        } catch (exc: Exception) {
            Log.e(LOG_ID, "OnNotifyData exception: " + exc.message.toString())
        }
    }

    override fun getNotification(): Notification {
        Channels.createNotificationChannel(this, ChannelType.WEAR_FOREGROUND)

        val pendingIntent = PackageUtils.getAppIntent(this, WearActivity::class.java, 11, false)

        return Notification.Builder(this, ChannelType.WEAR_FOREGROUND.channelId)
            .setContentTitle(getString(CR.string.forground_notification_descr))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }
}