package com.practice_app.image_detector.env;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.media.browse.MediaBrowser;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.practice_app.image_detector.customView.AutoFitTextureView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CameraConnectionFragment extends Fragment {
    private static final Logger LOGGER = new Logger();

    /**
     * 카메라 Preview 사이즈가 DESIRED_SIZE x DESIRED_SIZE 의 가능한 가장 작은 픽셀 단위로 선택됨
     */
    private static final int MINIMUM_PREVIEW_SIZE = 320;

    /**
     * 화면 회전을 JPEG orientation에 따라 변화
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    private static final String FRAGMENT_DIALOG = "dialog";

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * {@link Semaphore} 카메라가 꺼지기 전에 어플리케이션 종료를 막기 위함
     */
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);
    /**
     * {@link OnImageAvailableListener} 가능한 경우 frame들을 받아옴
     */
    private final OnImageAvailableListener imageListener;

    /**
     * input size는 Tensorflow에 의한 픽셀 사이즈
     */
    private final Size inputSize;
    /**
     * fragment inflate하기 위함
     */
    private final int layout;

    private final ConnectionCallback cameraConnectionCallback;


    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureProgressed(final CameraCaptureSession session, final CaptureRequest request, final CaptureResult partialResult) {
                }

                @Override
                public void onCaptureCompleted(final CameraCaptureSession session, final CaptureRequest request, final TotalCaptureResult result) {
                }
            };
    /**
     * {@link CameraDevice} 의 현재 ID
     */
    private String cameraId;
    /**
     * {@link AutoFitTextureView} 은 카메라 preview를 위함
     */
    private AutoFitTextureView textureView;
    /**
     * {@link CameraCaptureSession} 은 카메라 preview를 위함
     */
    private CameraCaptureSession captureSession;
    /**
     * 작동중인 {@link CameraDevice} 언급
     */
    private CameraDevice cameraDevice;
    /**
     * 카메라 센서의 각도
     */
    private Integer sensorOrientation;
    /**
     * {@link Size}는 카메라의 preview
     */
    private Size previewSize;
    /**
     * UI의 정상적이 작동을 위한 handler 구현
     */
    private HandlerThread backgroundThread;
    /**
     * Background에서 task를 진행하기 위해 {@link Handler} 사용
     */
    private Handler backgroundHandler;

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(final SurfaceTexture surfaceTexture, final int width, final int height) {
                    openCamera(width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(final SurfaceTexture surfaceTexture, int i, int i1) {

                }

                @Override
                public boolean onSurfaceTextureDestroyed(final SurfaceTexture surfaceTexture) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(final SurfaceTexture surfaceTexture) {

                }
            };

    /** An {@link ImageReader} that handles preview frame capture. */
    private ImageReader previewReader;
    /** {@link CaptureRequest.Builder} for the camera preview */
    private CaptureRequest.Builder previewRequestBuilder;
    /** {@link CaptureRequest} generated by {@link #previewRequestBuilder} */
    private CaptureRequest previewRequest;
    /** {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state. */
    private final CameraDevice.StateCallback stateCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(final CameraDevice cd) {
                    // This method is called when the camera is opened.  We start camera preview here.
                    cameraOpenCloseLock.release();
                    cameraDevice = cd;
                    createCameraPreviewSession();
                }

                @Override
                public void onDisconnected(final CameraDevice cd) {
                    cameraOpenCloseLock.release();
                    cd.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(final CameraDevice cd, final int error) {
                    cameraOpenCloseLock.release();
                    cd.close();
                    cameraDevice = null;
                    final Activity activity = getActivity();
                    if (null != activity) {
                        activity.finish();
                    }
                }
            };


    @SuppressLint("ValidFragment")
    private CameraConnectionFragment(
            final ConnectionCallback connectionCallback,
            final OnImageAvailableListener imageListener,
            final int layout,
            final Size inputSize) {
        this.cameraConnectionCallback = connectionCallback;
        this.imageListener = imageListener;
        this.layout = layout;
        this.inputSize = inputSize;
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the minimum of both, or an exact match if possible.
     *
     * @param choices The list of sizes that the camera supports for the intended output class
     * @param width   The minimum desired width
     * @param height  The minimum desired height
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    protected static Size chooseOptimalSize(final Size[] choices, final int width, final int height) {
        final int minSize = Math.max(Math.min(width, height), MINIMUM_PREVIEW_SIZE);
        final Size desiredSize = new Size(width, height);

        // Collect the supported resolutions that are at least as big as the preview Surface
        boolean exactSizeFound = false;
        final List<Size> bigEnough = new ArrayList<Size>();
        final List<Size> tooSmall = new ArrayList<Size>();
        for (final Size option : choices) {
            if (option.equals(desiredSize)) {
                // Set the size but don't return yet so that remaining sizes will still be logged.
                exactSizeFound = true;
            }

            if (option.getHeight() >= minSize && option.getWidth() >= minSize) {
                bigEnough.add(option);
            } else {
                tooSmall.add(option);
            }
        }

        LOGGER.i("Desired size: " + desiredSize + ", min size: " + minSize + "x" + minSize);
        LOGGER.i("Valid preview sizes: [" + TextUtils.join(", ", bigEnough) + "]");
        LOGGER.i("Rejected preview sizes: [" + TextUtils.join(", ", tooSmall) + "]");

        if (exactSizeFound) {
            LOGGER.i("Exact size match found.");
            return desiredSize;
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            final Size chosenSize = Collections.min(bigEnough, new CompareSizesByArea());
            LOGGER.i("Chosen size: " + chosenSize.getWidth() + "x" + chosenSize.getHeight());
            return chosenSize;
        } else {
            LOGGER.e("Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    public void setCamera(String cameraId) {
        this.cameraId = cameraId;
    }

    private void setUpCameraOutputs() {
        final Activity activity = getActivity();
        final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);    // 카메라 서비스 불러오기
        try {
            final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);   // 카메라 characteristics 불러옴

            final StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            previewSize =
                    chooseOptimalSize(
                            map.getOutputSizes(SurfaceTexture.class),
                            inputSize.getWidth(),
                            inputSize.getHeight()
                    );

            final int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                // 가로 모드인 경우 , Portrait : 세로 모드를 나타냄
                textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
            } else {
                textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
            }
        } catch (CameraAccessException e) {
            LOGGER.e(e, "예외 발생");
        } catch (final NullPointerException e) {
            throw new IllegalStateException("Camera2 API not supported");
        }

        cameraConnectionCallback.onPreviewSizeChosen(previewSize, sensorOrientation);
    }

    /**
     * {@link CameraConnectionFragment #cameraId}를 통해 얻은 특정 카메라를 사용
     */
    private void openCamera(final int width, final int height) {
        setUpCameraOutputs();
        configureTransform(width, height);
        final Activity activity = getActivity();
        final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening");
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        }catch (final CameraAccessException e){
            LOGGER.e(e, "예외 발생");
        } catch (final InterruptedException e){
            throw new RuntimeException("카메라 opening lock에 실패함", e);
        }
    }

    /** 현재 {@link CameraDevice}를 close */
    private void closeCamera(){
        try{
            cameraOpenCloseLock.acquire();
            if(null != captureSession){
                captureSession.close();
                captureSession = null;
            }
            if (null != cameraDevice){
                cameraDevice.close();
                cameraDevice = null;
            }
            if(null != previewReader){
                previewReader.close();
                previewReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("camera closing lock 할때 interrupt 발생");
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    /**
     * 'mTextureView'에서 필요한 {@link Matrix}로 변환 가능한 것들 선별
     * 카메라의 preview 사이즈가 setUpCameraOuptputs에서 정의 되었고 'mTextureView'가 고정 된 경우 사용
     * @param viewWidth 'mTextureView'의 넓이
     * @param viewHeight 'mTextureView'의 높이
     */
    private void configureTransform(final int viewWidth, final int viewHeight){
        final Activity activity = getActivity();
        if(null == textureView || null == previewSize || null == activity){
            return;
        }
        final int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        final Matrix matrix = new Matrix();
        final RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        final RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        final float centerX = viewRect.centerX();
        final float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation){
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            final float scale =
                    Math.max(
                            (float) viewHeight / previewSize.getHeight(),
                            (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }else if( Surface.ROTATION_180 == rotation){
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }


    /**
     * Callback for Activities to use to initialize their data once the selected preview size is
     * known.
     */
    public interface ConnectionCallback {
        void onPreviewSizeChosen(Size size, int cameraRotation);
    }


}
