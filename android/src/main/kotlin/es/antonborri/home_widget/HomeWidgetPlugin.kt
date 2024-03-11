package es.antonborri.home_widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import android.util.SizeF
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry


/** HomeWidgetPlugin */
class HomeWidgetPlugin : FlutterPlugin, MethodCallHandler, ActivityAware,
        EventChannel.StreamHandler, PluginRegistry.NewIntentListener {
    private lateinit var channel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private lateinit var context: Context

    private var activity: Activity? = null
    private var receiver: BroadcastReceiver? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "home_widget")
        channel.setMethodCallHandler(this)

        eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "home_widget/updates")
        eventChannel.setStreamHandler(this)
        context = flutterPluginBinding.applicationContext
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "saveWidgetData" -> {
                if (call.hasArgument("id") && call.hasArgument("data")) {
                    val id = call.argument<String>("id")
                    val data = call.argument<Any>("data")
                    val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                    if(data != null) {
                        when (data) {
                            is Boolean -> prefs.putBoolean(id, data)
                            is Float -> prefs.putFloat(id, data)
                            is String -> prefs.putString(id, data)
                            is Double -> prefs.putLong(id, java.lang.Double.doubleToRawLongBits(data))
                            is Int -> prefs.putInt(id, data)
                            else -> result.error("-10", "Invalid Type ${data!!::class.java.simpleName}. Supported types are Boolean, Float, String, Double, Long", IllegalArgumentException())
                        }
                    } else {
                        prefs.remove(id)
                    }
                    result.success(prefs.commit())
                } else {
                    result.error("-1", "InvalidArguments saveWidgetData must be called with id and data", IllegalArgumentException())
                }
            }
            "getWidgetData" -> {
                if (call.hasArgument("id")) {
                    val id = call.argument<String>("id")
                    val defaultValue = call.argument<Any>("defaultValue")

                    val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

                    val value = prefs.all[id] ?: defaultValue

                    if(value is Long) {
                        result.success(java.lang.Double.longBitsToDouble(value))
                    } else {
                        result.success(value)
                    }
                } else {
                    result.error("-2", "InvalidArguments getWidgetData must be called with id", IllegalArgumentException())
                }
            }
            "updateWidget" -> {
                val qualifiedName = call.argument<String>("qualifiedAndroidName")
                val className = call.argument<String>("android") ?: call.argument<String>("name")
                try {
                    val javaClass = Class.forName(qualifiedName ?: "${context.packageName}.${className}")
                    val intent = Intent(context, javaClass)
                    intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    val ids: IntArray = AppWidgetManager.getInstance(context.applicationContext).getAppWidgetIds(ComponentName(context, javaClass))
                    intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    context.sendBroadcast(intent)
                    result.success(true)
                } catch (classException: ClassNotFoundException) {
                    result.error("-3", "No Widget found with Name $className. Argument 'name' must be the same as your AppWidgetProvider you wish to update", classException)
                }
            }
            "setAppGroupId" -> {
                result.success(true)
            }
            "initiallyLaunchedFromHomeWidget" -> {
                return if (activity?.intent?.action?.equals(HomeWidgetLaunchIntent.HOME_WIDGET_LAUNCH_ACTION) == true) {
                    result.success(activity?.intent?.data?.toString() ?: "")
                } else {
                    result.success(null)
                }
            }
            "registerBackgroundCallback" -> {
                val dispatcher = ((call.arguments as Iterable<*>).toList()[0] as Number).toLong()
                val callback = ((call.arguments as Iterable<*>).toList()[1] as Number).toLong()
                saveCallbackHandle(context, dispatcher, callback)
                return result.success(true)
            }
            "isRequestPinWidgetSupported" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    return result.success(false)
                }

                val appWidgetManager = AppWidgetManager.getInstance(context.applicationContext)
                return result.success(appWidgetManager.isRequestPinAppWidgetSupported)
            }
            "requestPinWidget" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    return result.success(null)
                }

                val qualifiedName = call.argument<String>("qualifiedAndroidName")
                val className = call.argument<String>("android") ?: call.argument<String>("name")

                try {
                    val javaClass = Class.forName(qualifiedName ?: "${context.packageName}.${className}")
                    val myProvider = ComponentName(context, javaClass)

                    val appWidgetManager = AppWidgetManager.getInstance(context.applicationContext)

                    if (appWidgetManager.isRequestPinAppWidgetSupported) {
                        appWidgetManager.requestPinAppWidget(myProvider, null, null)
                    }

                    return result.success(null)
                } catch (classException: ClassNotFoundException) {
                    result.error("-4", "No Widget found with Name $className. Argument 'name' must be the same as your AppWidgetProvider you wish to update", classException)
                }
            }
            "getInstalledWidgets" -> {
                try {
                    val pinnedWidgetInfoList = getInstalledWidgets(context)
                    result.success(pinnedWidgetInfoList)
                } catch (e: Exception) {
                    result.error("-5", "Failed to get installed widgets: ${e.message}", null)
                }
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    private fun getInstalledWidgets(context: Context): List<Map<String, Any>> {
        val pinnedWidgetInfoList = mutableListOf<Map<String, Any>>()
        val appWidgetManager = AppWidgetManager.getInstance(context.applicationContext)
        val installedProviders = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appWidgetManager.getInstalledProvidersForPackage(context.packageName, null)
        } else {
            appWidgetManager.installedProviders.filter { it.provider.packageName == context.packageName }
        }
        for (provider in installedProviders) {
            val widgetIds = appWidgetManager.getAppWidgetIds(provider.provider)
            for (widgetId in widgetIds) {
                val widgetInfo = appWidgetManager.getAppWidgetInfo(widgetId)
                //AppWidgetProviderInfo  https://developer.android.com/reference/android/appwidget/AppWidgetProviderInfo#autoAdvanceViewId


                // Minimum width (in px) which the widget can be resized to. This field has no effect if it is greater than minWidth or if horizontal resizing isn't enabled (see resizeMode).
                //This field corresponds to the android:minResizeWidth attribute in the AppWidget meta-data file.
                val minResizeWidth = widgetInfo.minResizeWidth
                Log.d(TAG, "Widget Info - Min Resize Width: $minResizeWidth")

                // The default width of the widget when added to a host, in px. The widget will get at least this width, and will often be given more, depending on the host.
                // This field corresponds to the android:minWidth attribute in the AppWidget meta-data file
                val infoMinWidth = widgetInfo.minWidth
                Log.d(TAG, "Widget Info - Min Width: $infoMinWidth")

                // The default height of the widget when added to a host, in px. The widget will get at least this height, and will often be given more, depending on the host.
                //This field corresponds to the android:minHeight attribute in the AppWidget meta-data file.
                val infoMinHeight = widgetInfo.minHeight
                Log.d(TAG, "Widget Info - Min Height: $infoMinHeight")

                val infoMaxWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    widgetInfo.maxResizeWidth
                } else {
                    TODO("VERSION.SDK_INT < S")
                }
                Log.d(TAG, "Widget Info - Max Width: $infoMaxWidth")

                val infoMaxHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    widgetInfo.maxResizeHeight
                } else {
                    TODO("VERSION.SDK_INT < S")
                }
                Log.d(TAG, "Widget Info - Max Height: $infoMaxHeight")

                val infoCategory = widgetInfo.widgetCategory
                Log.d(TAG, "Widget Info - Category: $infoCategory")

                val options = appWidgetManager.getAppWidgetOptions(widgetId)

                if (options != null) {
                    //A bundle extra (int) that contains the lower bound on the current width, in dips, of a widget instance.
                    val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                    Log.d(TAG, "Options - Min Width: $minWidth")

                    //A bundle extra (int) that contains the lower bound on the current height, in dips, of a widget instance.
                    val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
                    Log.d(TAG, "Options - Min Height: $minHeight")

                    // A bundle extra (int) that contains the upper bound on the current width, in dips, of a widget instance.
                    val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
                    Log.d(TAG, "Options - Max Width: $maxWidth")

                    val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                    Log.d(TAG, "Options - Max Height: $maxHeight")

                    val restoreCompleted = options.getBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED)
                    Log.d(TAG, "Options - Restore Completed: $restoreCompleted")

                    // Test console output

                    // before resize :

//                    [+2316 ms] D/HomeWidgetPlugin( 7247): Widget Info - Min Resize Width: 468
//                    [        ] D/HomeWidgetPlugin( 7247): Widget Info - Min Width: 468
//                    [        ] D/HomeWidgetPlugin( 7247): Widget Info - Min Height: 303
//                    [        ] D/HomeWidgetPlugin( 7247): Widget Info - Max Width: 0
//                    [        ] D/HomeWidgetPlugin( 7247): Widget Info - Max Height: 0
//                    [        ] D/HomeWidgetPlugin( 7247): Widget Info - Category: 1
//                    [        ] D/HomeWidgetPlugin( 7247): Options - Min Width: 209
//                    [        ] D/HomeWidgetPlugin( 7247): Options - Min Height: 132
//                    [        ] D/HomeWidgetPlugin( 7247): Options - Max Width: 376
//                    [        ] D/HomeWidgetPlugin( 7247): Options - Max Height: 272
//                    [        ] D/HomeWidgetPlugin( 7247): Options - Restore Completed: false
//                    [        ] D/HomeWidgetPlugin( 7247): Options - Sizes: [209.81818x272.0, 376.72726x132.36363, 376.72726x132.36363]

                        // after resize :

//                    [+1232 ms] D/HomeWidgetPlugin( 7247): Widget Info - Min Resize Width: 468
//                    [        ] D/HomeWidgetPlugin( 7247): Widget Info - Min Width: 468
//                    [        ] D/HomeWidgetPlugin( 7247): Widget Info - Min Height: 303
//                    [        ] D/HomeWidgetPlugin( 7247): Widget Info - Max Width: 0
//                    [        ] D/HomeWidgetPlugin( 7247): Widget Info - Max Height: 0
//                    [        ] D/HomeWidgetPlugin( 7247): Widget Info - Category: 1
//                    [        ] D/HomeWidgetPlugin( 7247): Options - Min Width: 360
//                    [        ] D/HomeWidgetPlugin( 7247): Options - Min Height: 280
//                    [        ] D/HomeWidgetPlugin( 7247): Options - Max Width: 638
//                    [        ] D/HomeWidgetPlugin( 7247): Options - Max Height: 560
//                    [        ] D/HomeWidgetPlugin( 7247): Options - Restore Completed: false
//                    [        ] D/HomeWidgetPlugin( 7247): Options - Sizes: [360.36365x560.0, 638.5455x280.72726, 638.5455x280.72726]
                }
                pinnedWidgetInfoList.add(widgetInfoToMap(widgetId, widgetInfo))
            }
        }
        return pinnedWidgetInfoList
    }

    private fun widgetInfoToMap(widgetId: Int, widgetInfo: AppWidgetProviderInfo): Map<String, Any> {
        val label = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            widgetInfo.loadLabel(context.packageManager).toString()
        } else {
            @Suppress("DEPRECATION")
            widgetInfo.label
        }

        return mapOf(
            WIDGET_INFO_KEY_WIDGET_ID to widgetId,
            WIDGET_INFO_KEY_ANDROID_CLASS_NAME to widgetInfo.provider.shortClassName,
            WIDGET_INFO_KEY_LABEL to label
        )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    companion object {
        private  const val TAG = "HomeWidgetPlugin"
        private const val PREFERENCES = "HomeWidgetPreferences"

        private const val INTERNAL_PREFERENCES = "InternalHomeWidgetPreferences"
        private const val CALLBACK_DISPATCHER_HANDLE = "callbackDispatcherHandle"
        private const val CALLBACK_HANDLE = "callbackHandle"

        private const val WIDGET_INFO_KEY_WIDGET_ID = "widgetId"
        private const val WIDGET_INFO_KEY_ANDROID_CLASS_NAME = "androidClassName"
        private const val WIDGET_INFO_KEY_LABEL = "label"

        private fun saveCallbackHandle(context: Context, dispatcher: Long, handle: Long) {
            context.getSharedPreferences(INTERNAL_PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(CALLBACK_DISPATCHER_HANDLE, dispatcher)
                    .putLong(CALLBACK_HANDLE, handle)
                    .apply()
        }

        fun getDispatcherHandle(context: Context): Long =
                context.getSharedPreferences(INTERNAL_PREFERENCES, Context.MODE_PRIVATE).getLong(CALLBACK_DISPATCHER_HANDLE, 0)

        fun getHandle(context: Context): Long =
                context.getSharedPreferences(INTERNAL_PREFERENCES, Context.MODE_PRIVATE).getLong(CALLBACK_HANDLE, 0)

        fun getData(context: Context): SharedPreferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addOnNewIntentListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        unregisterReceiver()
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addOnNewIntentListener(this)
    }

    override fun onDetachedFromActivity() {
        unregisterReceiver()
        activity = null
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        receiver = createReceiver(events)
    }

    override fun onCancel(arguments: Any?) {
        unregisterReceiver()
        receiver = null
    }

    private fun createReceiver(events: EventChannel.EventSink?): BroadcastReceiver {
        return object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action.equals(HomeWidgetLaunchIntent.HOME_WIDGET_LAUNCH_ACTION)) {
                    events?.success(intent?.data?.toString() ?: true)
                }
            }

        }
    }

    private fun unregisterReceiver() {
        try {
            if (receiver != null) {
                context.unregisterReceiver(receiver)
            }
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
    }

    override fun onNewIntent(intent: Intent): Boolean {
        receiver?.onReceive(context, intent)
        return receiver != null
    }
}
