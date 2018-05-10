package science.credo.credomobiledetektor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.hardware.*
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.preference.PreferenceManager
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import science.credo.credomobiledetektor.database.DataManager
import science.credo.credomobiledetektor.detection.CameraSurfaceHolder
import science.credo.credomobiledetektor.detection.Hit
import science.credo.credomobiledetektor.events.BatteryEvent
import science.credo.credomobiledetektor.events.DetectorStateEvent
import science.credo.credomobiledetektor.info.ConfigurationInfo
import science.credo.credomobiledetektor.info.IdentityInfo
import science.credo.credomobiledetektor.info.PowerConnectionReceiver
import science.credo.credomobiledetektor.network.NetworkInterface
import science.credo.credomobiledetektor.network.ServerInterface
import science.credo.credomobiledetektor.network.messages.DetectionRequest
import science.credo.credomobiledetektor.network.messages.PingRequest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


class DetectorService : Service(), SharedPreferences.OnSharedPreferenceChangeListener, SensorEventListener {
    companion object {
        val TAG = "DetectorService"
        var count = 0
    }

    private val state = DetectorStateEvent(false)
    private var batteryState = BatteryEvent()

    private var mWakeLock: PowerManager.WakeLock? = null;
    private var mCamera: Camera? = null;
    private var mSurfaceView: SurfaceView? = null
    private var mWindowManager: WindowManager? = null
    private val mReceiver = PowerConnectionReceiver()
    private var mConfigurationInfo: ConfigurationInfo? = null
    private var mSensorManager: SensorManager? = null

    override fun onBind(intent: Intent): IBinder? {
        throw UnsupportedOperationException("Not yet implemented")
    }

    override fun onCreate() {
        Log.d(TAG,"onCreate: " + ++count)
        super.onCreate()
        state.running = true
        EventBus.getDefault().register(this)
        emitStateChange()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        mConfigurationInfo = ConfigurationInfo(baseContext)
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CREDO_LOCK")
        mWakeLock?.acquire()

        startCamera()
        startPing()
        Log.d(TAG,"onStartCommand " + count)

        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED)
        filter.addAction(Intent.ACTION_POWER_CONNECTED)
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        batteryState = PowerConnectionReceiver.parseIntent(registerReceiver(mReceiver, filter))

        mSensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val temperatureSensor = mSensorManager?.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
        val accelerometerSensor = mSensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        mSensorManager?.registerListener(this, temperatureSensor, SensorManager.SENSOR_DELAY_NORMAL)
        mSensorManager?.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL)

        PreferenceManager.getDefaultSharedPreferences(applicationContext).registerOnSharedPreferenceChangeListener(this)

        return START_STICKY;
    }

    override fun onDestroy() {
        mSensorManager?.unregisterListener(this)
        mWakeLock?.release()
        stopCamera()
        stopPing()
        Log.d(TAG,"onDestroy: " + --count)

        unregisterReceiver(mReceiver)
        PreferenceManager.getDefaultSharedPreferences(applicationContext).unregisterOnSharedPreferenceChangeListener(this)

        state.running = false
        emitStateChange()
        EventBus.getDefault().unregister(this)
        super.onDestroy()
    }

    private fun checkConditionsAndEmitState() : Boolean {
        if (mConfigurationInfo!!.isChargerOnly && !batteryState.isCharging) {
            setState(DetectorStateEvent.StateType.Warning, getString(R.string.warning_condition_charging))
            return false
        }

        if (batteryState.batteryPct < mConfigurationInfo!!.batteryLevel) {
            setState(DetectorStateEvent.StateType.Warning, getString(R.string.warning_condition_low_battery))
            return false
        }

        if (state.temperature > mConfigurationInfo!!.maxTemperature) {
            setState(DetectorStateEvent.StateType.Warning, getString(R.string.warning_condition_too_heat))
            return false
        }

        if (state.type < DetectorStateEvent.StateType.Error) {
            setState(DetectorStateEvent.StateType.Normal, "")
        }

        return true
    }

    fun startStopOnConditionChange() {
        val canProcess = checkConditionsAndEmitState()
        Log.d(TAG,"startStopOnConditionChange: canProcess: $canProcess")
        when {
            canProcess && !state.cameraOn -> startCamera()
            !canProcess && state.cameraOn -> stopCamera()
        }
    }

    override fun onSharedPreferenceChanged(p0: SharedPreferences?, p1: String?) {
        startStopOnConditionChange()
    }

    fun startCamera() {
        Log.d(TAG, "startCamera: " + count)
        if (!checkConditionsAndEmitState()) {
            Log.d(TAG, "startCamera: start not allowed")
            return
        }
        if (state.cameraOn) {
            Log.d(TAG,"startCamera: camera already running")
            return
        }

        Log.d(TAG, "startCamera: starting camera")
        try {
            state.cameraOn = false
            mCamera = Camera.open()
            state.cameraOn = true
        } catch (e: RuntimeException) {
            if (CredoApplication.isEmulator()) {
                Toast.makeText(this, R.string.error_emulator, Toast.LENGTH_LONG).show()
                setState(DetectorStateEvent.StateType.Error, getString(R.string.error_emulator))
            } else {
                val msg = getString(R.string.error_camera, e.message)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                setState(DetectorStateEvent.StateType.Error, msg)
            }
            return
        }
        val parameters: Camera.Parameters = mCamera!!.parameters;
        parameters.setRecordingHint(true)
        val sizes = parameters.supportedPreviewSizes
        val index = sizes.size/2 // ~medium resolution
//        val index = 0 // max resolution
        for (size in sizes) {
            Log.d(TAG, "width: ${size.width}, height: ${size.height}")
        }
        Log.d(TAG,"will use: ${sizes[index].width}, height: ${sizes[index].height}")
        parameters.setPreviewSize(sizes[index].width, sizes[index].height)
        mCamera?.parameters = parameters;

        mSurfaceView = SurfaceView(this)
        mWindowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
                2,
                2,
                -5000,
                5000,
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE + WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT)

        mSurfaceView?.holder?.addCallback(CameraSurfaceHolder(mCamera!!, baseContext))

        mWindowManager?.addView(mSurfaceView, params)
        mSurfaceView?.setZOrderOnTop(false)
        mSurfaceView?.visibility = View.VISIBLE
        setState(DetectorStateEvent.StateType.Normal, getString(R.string.status_fragment_running))
    }

    fun stopCamera() {
        Log.d(TAG,"stopCamera")
        if (!state.cameraOn) {
            Log.d(TAG, "stopCamera: camera already stopped")
            return
        }

        state.cameraOn = false
        mCamera?.setPreviewCallbackWithBuffer(null);
        mCamera?.stopPreview()
        mWindowManager?.removeView(mSurfaceView)
        mCamera?.release()
        emitStateChange()
    }

    val scheduler = Executors.newSingleThreadScheduledExecutor()

    private fun startPing() {
        /**
         * Ping scheduler.
         */
        scheduler.scheduleAtFixedRate({
            //@TODO send ping
//            NetworkInterface.getInstance(this).sendPing()
//            ServerInterface.getDefault().ping(PingRequest(0, System.currentTimeMillis()))
        }, 0, 60, TimeUnit.MINUTES)
        /**
         * Detection synchronization scheduler.
         */
        scheduler.scheduleAtFixedRate({
//            NetworkInterface.getInstance(this).sendHitsToNetwork()
            val hits: MutableList<Hit> = DataManager.getInstance(this).getHits()
            val deviceInfo = IdentityInfo.getInstance(this).getIdentityData()
            ServerInterface.getDefault(this).sendDetections(DetectionRequest(hits, deviceInfo))
        }, 0, 10, TimeUnit.MINUTES)
        /**
         * Database cleanup scheduler
         */
        scheduler.scheduleAtFixedRate({
            val dm = DataManager.getInstance(this)
            dm.trimHitsDb()
        }, 0, 24, TimeUnit.HOURS)
    }
    private fun stopPing() {
        scheduler.shutdown()
    }

    private fun setState(type: DetectorStateEvent.StateType, msg: String) {
        state.status = msg
        state.type = type
        emitStateChange()
    }

    private fun emitStateChange() {
        EventBus.getDefault().post(state.copy())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            state.accX = event.values[0].toInt()
            state.accY = event.values[1].toInt()
            state.accZ = event.values[2].toInt()
        } else if (event?.sensor?.type == Sensor.TYPE_AMBIENT_TEMPERATURE) {
            state.temperature = event.values[0].toInt()
        }
        //startStopOnConditionChange()
        //emitStateChange()
    }

    @Subscribe
    fun onBatteryEvent(batteryEvent: BatteryEvent) {
        batteryState = batteryEvent
        startStopOnConditionChange()
    }
}
