package com.munger.sweepano;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.YuvImage;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.renderscript.Script;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Allocation;
import android.renderscript.Type;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;

import com.xxxyyy.testcamera2.ScriptC_yuv420888;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.security.Permission;
import java.util.ArrayList;
import java.util.List;

import us.yydcdut.camera2.Progress;
import us.yydcdut.camera2.ScriptC_yuvtorgb;

/**
 * Created by hallmarklabs on 7/15/17.
 */

public class CaptureFragment extends Fragment
{
    private SurfaceView previewSurfaceView;
    private View baseView;
    private ImageView previewView;
    private Button shutterButton;
    private CameraManager cameraMgr;

    private String selectedCameraId;

    private CameraDevice cameraDevice = null;
    private CameraCaptureSession captureSession;

    private ImageReader capturer;
    private Surface captureSurface;
    private Surface previewSurface;
    private ScriptC_yuv420888 mYuv420;

    private SensorManager sensorManager;
    private Sensor sensor;
    private RotationListener rotationListener;

    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {
        baseView = inflater.inflate(R.layout.capture_fragment, container, false);

        previewSurfaceView = (SurfaceView) baseView.findViewById(R.id.capture_surfaceView);
        shutterButton = (Button) baseView.findViewById(R.id.capture_shutter);
        previewView = (ImageView) baseView.findViewById(R.id.capture_imageView);

        rs = RenderScript.create(MainActivity.getInstance());
        mYuv420 = new ScriptC_yuv420888(rs);

        setupListeners();
        return baseView;
    }

    private final int REQUEST_ID = 489;

    private void setupListeners()
    {
        shutterButton.setOnClickListener(new View.OnClickListener() {public void onClick(View view)
        {
            toggleCapture();
        }});
        shutterButton.setEnabled(false);

        previewSurfaceView.getHolder().addCallback(new HolderCallback(this));

        setupCamera();

        sensorManager = (SensorManager) MainActivity.getInstance().getSystemService(Context.SENSOR_SERVICE);
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        rotationListener = new RotationListener();
        sensorManager.registerListener(rotationListener, sensor, SensorManager.SENSOR_DELAY_FASTEST);
    }

    private class HolderCallback implements SurfaceHolder.Callback
    {
        private CaptureFragment parent;
        public HolderCallback(CaptureFragment parent)
        {
            this.parent = parent;
        }

        @Override
        public void surfaceCreated(SurfaceHolder surfaceHolder)
        {
            parent.surfaceHolder = surfaceHolder;

            synchronized (previewLock)
            {
                if (parent.surfaceHolder != null && parent.cameraDevice != null)
                    parent.setupPreviewSurface();
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {}

        @Override
        public void surfaceDestroyed(SurfaceHolder surfaceHolder) {}
    }

    private void setupCamera()
    {
        int permissionCheck = ContextCompat.checkSelfPermission(MainActivity.getInstance(), Manifest.permission.CAMERA);

        if (permissionCheck == PackageManager.PERMISSION_DENIED)
        {
            ActivityCompat.requestPermissions(MainActivity.getInstance(), new String[]{Manifest.permission.CAMERA}, REQUEST_ID);
            return;
        }

        try
        {
            cameraMgr = (CameraManager) MainActivity.getInstance().getSystemService(Context.CAMERA_SERVICE);
            pickCameraId();
            cameraMgr.openCamera(selectedCameraId, new CameraStateListener(this), null);
        }
        catch (CameraAccessException e){

        }
    }

    private SurfaceHolder surfaceHolder;
    private Object previewLock = new Object();

    private void setupPreviewSurface()
    {
        try
        {
            CameraDimStr str = pickSurfaceDimensions(SurfaceType.CAPTURE);
            surfaceHolder.setFixedSize(str.size.getWidth(), str.size.getHeight());

            previewSurface = surfaceHolder.getSurface();

            setupCamera2();
        }
        catch (CameraAccessException e){

        }
    }

    private RenderScript rs;

    private void setupCaptureSurface()
    {
        try
        {
            CameraDimStr str = pickSurfaceDimensions(SurfaceType.CAPTURE);
            capturer = ImageReader.newInstance(str.size.getWidth(), str.size.getHeight(), str.format, 10);
            captureSurface = capturer.getSurface();

            capturer.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {public void onImageAvailable(ImageReader reader)
            {
                Image img = reader.acquireLatestImage();

                if (img != null)
                {
                    Bitmap bmp = YUVtoRGB(img);
                    previewView.setImageBitmap(bmp);
                    img.close();
                    stopCapture();
                }
            }}, null);
        }
        catch(CameraAccessException e)
        {}
    }

    private Bitmap YUVtoRGB(Image image)
    {
        int width = image.getWidth();
        int height = image.getHeight();

        // Get the three image planes
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        byte[] y = new byte[buffer.remaining()];
        buffer.get(y);

        buffer = planes[1].getBuffer();
        byte[] u = new byte[buffer.remaining()];
        buffer.get(u);

        buffer = planes[2].getBuffer();
        byte[] v = new byte[buffer.remaining()];
        buffer.get(v);

        // get the relevant RowStrides and PixelStrides
        // (we know from documentation that PixelStride is 1 for y)
        int yRowStride= planes[0].getRowStride();
        int uvRowStride= planes[1].getRowStride();  // we know from   documentation that RowStride is the same for u and v.
        int uvPixelStride= planes[1].getPixelStride();  // we know from   documentation that PixelStride is the same for u and v.

        // Y,U,V are defined as global allocations, the out-Allocation is the Bitmap.
        // Note also that uAlloc and vAlloc are 1-dimensional while yAlloc is 2-dimensional.
        Type.Builder typeUcharY = new Type.Builder(rs, Element.U8(rs));
        typeUcharY.setX(yRowStride).setY(height);
        Allocation yAlloc = Allocation.createTyped(rs, typeUcharY.create());
        yAlloc.copyFrom(y);
        mYuv420.set_ypsIn(yAlloc);

        Type.Builder typeUcharUV = new Type.Builder(rs, Element.U8(rs));
        // note that the size of the u's and v's are as follows:
        //      (  (width/2)*PixelStride + padding  ) * (height/2)
        // =    (RowStride                          ) * (height/2)
        // but I noted that on the S7 it is 1 less...
        typeUcharUV.setX(u.length);
        Allocation uAlloc = Allocation.createTyped(rs, typeUcharUV.create());
        uAlloc.copyFrom(u);
        mYuv420.set_uIn(uAlloc);

        Allocation vAlloc = Allocation.createTyped(rs, typeUcharUV.create());
        vAlloc.copyFrom(v);
        mYuv420.set_vIn(vAlloc);

        // handover parameters
        mYuv420.set_picWidth(width);
        mYuv420.set_uvRowStride (uvRowStride);
        mYuv420.set_uvPixelStride (uvPixelStride);

        Bitmap outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Allocation outAlloc = Allocation.createFromBitmap(rs, outBitmap, Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);

        Script.LaunchOptions lo = new Script.LaunchOptions();
        lo.setX(0, width);  // by this we ignore the y’s padding zone, i.e. the right side of x between width and yRowStride
        lo.setY(0, height);

        mYuv420.forEach_doConvert(outAlloc,lo);
        outAlloc.copyTo(outBitmap);

        return outBitmap;
    }

    void onCameraPrepared(CameraDevice device)
    {
        this.cameraDevice = device;

        setupCaptureSurface();
        synchronized (previewLock)
        {
            if (surfaceHolder != null && cameraDevice != null)
                setupPreviewSurface();
        }
    }

    void setupCamera2()
    {
        try
        {
            List<Surface> sarg = new ArrayList<Surface>();
            sarg.add(previewSurface);
            sarg.add(captureSurface);
            cameraDevice.createCaptureSession(sarg, new CameraCaptureSession.StateCallback()
            {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    captureSession = cameraCaptureSession;
                    startPreview();
                    shutterButton.setEnabled(true);
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {

                }
            }, null);
        }
        catch(CameraAccessException e){}
    }

    private void startPreview()
    {
        try
        {
            CaptureRequest.Builder reqBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            reqBuilder.addTarget(previewSurface);
            CaptureRequest req = reqBuilder.build();

            captureSession.setRepeatingRequest(req, new CameraCaptureSession.CaptureCallback() {}, null);
        }
        catch(CameraAccessException e)
        {}

    }

    private String pickCameraId() throws CameraAccessException
    {
        String[] ids = cameraMgr.getCameraIdList();
        selectedCameraId = null;

        for (String id : ids)
        {
            CameraCharacteristics ch = cameraMgr.getCameraCharacteristics(id);
            int facing = ch.get(CameraCharacteristics.LENS_FACING);

            if (facing == CameraCharacteristics.LENS_FACING_BACK)
            {
                selectedCameraId = id;
                break;
            }
        }

        if (selectedCameraId == null)
            selectedCameraId = ids[0];

        return selectedCameraId;
    }

    private enum SurfaceType
    {
        PREVIEW,
        CAPTURE
    }

    private class CameraDimStr
    {
        public SurfaceType type;
        public int format;
        public Size size;
    }

    private CameraDimStr pickSurfaceDimensions(SurfaceType type) throws CameraAccessException
    {
        CameraDimStr ret = new CameraDimStr();
        ret.type = type;
        CameraCharacteristics ch = cameraMgr.getCameraCharacteristics(selectedCameraId);
        StreamConfigurationMap configs = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        getFormat(configs, ret);
        getSize(configs, ret);

        return ret;
    }

    private void getFormat(StreamConfigurationMap configs, CameraDimStr str)
    {
        int[] formats = configs.getOutputFormats();
        int previewFormat = -1;

        for(int format : formats)
        {
            if (format == ImageFormat.YUV_420_888)
            {
                previewFormat = format;
                break;
            }
        }

        if (previewFormat == -1)
            previewFormat = formats[0];

        str.format = previewFormat;
    }

    private void getSize(StreamConfigurationMap configs, CameraDimStr str)
    {
        Size ret = null;
        Size[] sizes = configs.getOutputSizes(str.format);

        if (str.type == SurfaceType.PREVIEW)
            ret = sizes[sizes.length - 1];
        else
        {
            Size previewSize = null;
            for (Size sz : sizes)
            {
                if (sz.getWidth() < 3072)
                {
                    previewSize = sz;
                    break;
                }
            }

            if (previewSize == null)
                previewSize = sizes[0];

            ret = previewSize;
        }

        str.size = ret;
    }

    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults)
    {
        if (requestCode != REQUEST_ID || grantResults.length == 0)
            return;

        if (grantResults[0] == PackageManager.PERMISSION_GRANTED)
            setupCamera();
    }

    private boolean capturePreparing = false;
    private boolean captureRunning = false;
    private Object captureLock = new Object();

    private void toggleCapture()
    {
        boolean doStartCapture = false;
        synchronized (captureLock)
        {
            if (!captureRunning)
                doStartCapture = true;
        }

        if (doStartCapture)
            startCapture();
        else
            stopCapture();
    }

    private void startCapture()
    {
        synchronized (captureLock)
        {
            if (capturePreparing)
                return;

            capturePreparing = true;
            shutterButton.setEnabled(false);
        }

        try
        {
            rotationListener.resetDelta();
            CaptureRequest.Builder reqBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            reqBuilder.addTarget(captureSurface);
            CaptureRequest req = reqBuilder.build();

            captureSession.setRepeatingRequest(req, new CameraCaptureSession.CaptureCallback() {}, null);
        }
        catch(CameraAccessException e)
        {
            synchronized (captureLock)
            {
                capturePreparing = false;
                captureRunning = false;
                shutterButton.setEnabled(true);
            }

            return;
        }

        synchronized (captureLock)
        {
            capturePreparing = false;
            captureRunning = true;
            shutterButton.setEnabled(true);
        }
    }

    private void stopCapture()
    {
        synchronized (captureLock)
        {
            if (capturePreparing)
                return;

            capturePreparing = true;
            shutterButton.setEnabled(false);
        }

        try
        {
            captureSession.stopRepeating();
            startPreview();
        }
        catch(CameraAccessException e)
        {}

        synchronized (captureLock)
        {
            capturePreparing = false;
            captureRunning = false;
            shutterButton.setEnabled(true);
        }
    }

    private class RotationListener implements SensorEventListener
    {
        private static final float NS2S = 1.0f / 1000000000.0f;

        public float yAccel;
        public float yVel;
        public float yRot;
        public float timestamp;

        @Override
        public void onSensorChanged(SensorEvent sensorEvent)
        {
            yAccel = sensorEvent.values[1];

            float newTimestamp = sensorEvent.timestamp * NS2S;
            float tsDelta = (timestamp == 0) ? 0 : newTimestamp - timestamp;

            yVel = yVel + yAccel * tsDelta;
            yRot = yRot + yVel * tsDelta;

            //Log.d("sweepano", "accel data ts: " + tsDelta + " accel: " + yAccel + " val: " + yVel + " rot: " + yRot );

            timestamp = newTimestamp;
        }

        public void resetDelta()
        {
            timestamp = 0;
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i)
        {}
    }

    private class CameraStateListener extends CameraDevice.StateCallback
    {
        private CaptureFragment parent;
        public CameraStateListener(CaptureFragment parent)
        {
            this.parent = parent;
        }

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice)
        {
            parent.onCameraPrepared(cameraDevice);
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice)
        {

        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i)
        {

        }
    }
}
