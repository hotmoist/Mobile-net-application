package com.practice_app.image_detector;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import android.app.Fragment;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Toast;

import com.practice_app.image_detector.env.Logger;

public abstract class MainActivity extends AppCompatActivity
        implements ImageReader.OnImageAvailableListener,
        Camera.PreviewCallback,
        View.OnClickListener,
        AdapterView.OnItemSelectedListener {
    private static final Logger LOGGER = new Logger();

    private static final int PERMISSIONS_REQUEST = 1;

    private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
    protected int previewWidth = 0;
    protected int previewHeight = 0;
    private Handler handler;
    private HandlerThread handlerThread;
    private boolean useCamera2API;


    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        LOGGER.d("onCreate" + this);
        super.onCreate(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // keep the screen on

        setContentView(R.layout.activity_main);

        if (hasPermission()) {
            setFragment();
        } else {
            requestPermission();
        }
    }

    @Override
    public void onPreviewFrame(byte[] bytes, Camera camera) {

    }

    @Override
    public void onImageAvailable(ImageReader imageReader) {

    }

    @Override
    public void onClick(View view) {

    }

    @Override
    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {

    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {

    }

    // ????????? ???????????? ????????? ?????? ?????? ?????? ??????
    private boolean isHardwareLevelSupported(

            CameraCharacteristics characteristics, int requiredLevel
    ) {
        int deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            return requiredLevel == deviceLevel;
        }

        return requiredLevel <= deviceLevel;
    }

    private String chooseCamera() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (final String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);

                // ?????? ????????? ?????? ??????, ?????? ????????? ?????? ??????
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                final StreamConfigurationMap map =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                if (map == null) {
                    continue;
                }

                // Fallback to camera1 API for internal cameras that don't have full support.
                // This should help with legacy situations where using the camera2 API causes
                // distorted or otherwise broken previews.
                useCamera2API =
                        (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) || isHardwareLevelSupported(
                                characteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
                LOGGER.i("Camera API lv2?: %s", useCamera2API);
                return cameraId;
            }
        } catch (CameraAccessException e) {
            LOGGER.e(e, "????????? ????????? ??????????????????");
        }

        return null;
    }

    protected void setFragment() {
        String cameraId = chooseCamera();

        Fragment fragment;
        if (useCamera2API) {
            // ???????????? ???????????? ?????? fragment ??????
            CameraConnectionFragment camera2Fragment =
                    CameraConnectionFragment.newInstance(
                            new CameraConnectionFragment.ConnectionCallback() {
                                @Override
                                public void onPreviewSizeChosen(final Size size, final int rotation) {
                                    previewHeight = size.getHeight();
                                    previewWidth = size.getWidth();
                                    MainActivity.this.onPreviewSizeChosen(size, rotation);
                                }
                            },
                            this,
                            getLayoutId(),
                            getDesiredPreviewFrameSize());

            camera2Fragment.setCamera(cameraId);
            fragment = camera2Fragment;
        } else {
            fragment =
                    new LegacyCameraConnectionFragment(this, getLayoutId(), getDesiredPreviewFrameSize());
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST) {
            if (allPermissionGranted(grantResults)) {
                setFragment();
            } else {
                requestPermission();
            }
        }
    }

    private static boolean allPermissionGranted(final int[] grantResults) {
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA)) {
                Toast.makeText(
                        MainActivity.this,
                        "????????? ?????? ????????? ???????????????",
                        Toast.LENGTH_LONG
                ).show();
            }
            requestPermissions(new String[]{PERMISSION_CAMERA}, PERMISSIONS_REQUEST);
        }
    }


    protected abstract void processImage();

    protected abstract void onPreviewSizeChosen(final Size size, final int rotation);

    protected abstract int getLayoutId();

    protected abstract Size getDesiredPreviewFrameSize();

    protected abstract void onInferenceConfigurationChanged();
}