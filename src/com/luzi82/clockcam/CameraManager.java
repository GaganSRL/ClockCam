package com.luzi82.clockcam;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.Size;

public class CameraManager {

	Camera mCamera;
	Timer mTimer = new Timer();

	TimerTask mNextTask = null;

	String mSaveDirectory = null; // sync

	long mNextShot = -1;
	String mNextFilename = null;
	// long mPeriod = 15 * MIN;
	long mPeriod;
	long mPrepareTime = 5000;

	static final SimpleDateFormat FILE_FORMAT = new SimpleDateFormat("yyyyMMdd-HHmmss");

	enum CameraState {
		NULL, CREATE, WAIT, PREVIEW, SHOT, DONE
	}

	CameraState mCameraState = CameraState.NULL;
	boolean mShouldRunCamera = false;

	TimeManager mTimeManager;

	public CameraManager(TimeManager aTimeManager) {
		mTimeManager = aTimeManager;
	}

	public synchronized void start() {
		if (mShouldRunCamera)
			return;
		mShouldRunCamera = true;
		if (mCameraState == CameraState.NULL) {
			nextStateTask(CameraState.CREATE, new StateCreateTimeout(), 0);
		}
	}

	public synchronized void stopLater() {
		if (!mShouldRunCamera)
			return;
		mShouldRunCamera = false;
		if ((mCameraState == CameraState.CREATE) || (mCameraState == CameraState.WAIT) || (mCameraState == CameraState.PREVIEW)) {
			if (mNextTask != null) {
				mNextTask.cancel();
				mNextTask = null;
			}
			killCamera();
		}
	}

	synchronized void beginWait() {
		mNextShot = calNextShot();
		nextStateTask(CameraState.WAIT, new StateWaitTimeout(), (mNextShot - mPrepareTime) - mTimeManager.currentRealTime());
	}

	class StateCreateTimeout extends TimerTask {
		@Override
		public void run() {
			synchronized (CameraManager.this) {
				ClockCamActivity.d(this.getClass().getName());
				if (mNextTask != this)
					return;
				mNextTask = null;
				if (mCamera == null) {
					mCamera = Camera.open();
					applyCameraParameter();
				}
				beginWait();
			}
		}
	}

	class StateWaitTimeout extends TimerTask {
		@Override
		public void run() {
			synchronized (CameraManager.this) {
				ClockCamActivity.d(this.getClass().getName());
				if (mNextTask != this)
					return;
				mNextTask = null;
				mCamera.startPreview();
				Date nextShotDate = new Date(mNextShot);
				mNextFilename = FILE_FORMAT.format(nextShotDate) + ".jpg";
				nextStateTask(CameraState.PREVIEW, new StatePreviewTimeout(), mNextShot - mTimeManager.currentRealTime());
			}
		}
	}

	class StatePreviewTimeout extends TimerTask {
		@Override
		public void run() {
			synchronized (CameraManager.this) {
				ClockCamActivity.d(this.getClass().getName());
				if (mNextTask != this)
					return;
				mNextTask = null;
				mCameraState = CameraState.SHOT;
				mCamera.takePicture(null, null, mStatePicture);
			}
		}
	}

	PictureCallback mStatePicture = new PictureCallback() {
		@Override
		public void onPictureTaken(byte[] data, Camera camera) {
			synchronized (CameraManager.this) {
				ClockCamActivity.d(this.getClass().getName());
				if (mNextFilename != null) {
					ClockCamActivity.d(String.format("onPictureTaken %d", data.length));
					try {
						BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(new File(mSaveDirectory, mNextFilename)));
						bos.write(data);
						bos.flush();
						bos.close();
					} catch (FileNotFoundException e) {
						e.printStackTrace();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				mNextFilename = null;
				nextStateTask(CameraState.DONE, new StateDoneTimeout(), 0);
			}
		}
	};

	class StateDoneTimeout extends TimerTask {
		@Override
		public void run() {
			synchronized (CameraManager.this) {
				ClockCamActivity.d(this.getClass().getName());
				if (mNextTask != this)
					return;
				if (mShouldRunCamera) {
					mNextTask = null;
					beginWait();
				} else {
					killCamera();
				}
			}
		}
	}

	static public class CameraParameter {
		int mPictureWidth;
		int mPictureHeight;
		String whiteBalance;
	}

	CameraParameter mCameraParameter;

	public synchronized void setCameraParameter(CameraParameter aCameraParameter) {
		mCameraParameter = aCameraParameter;
		applyCameraParameter();
	}

	private synchronized void applyCameraParameter() {
		if (mCamera == null)
			return;
		boolean change = false;

		Camera.Parameters param = mCamera.getParameters();

		Size size = param.getPictureSize();
		if ((size.width != mCameraParameter.mPictureWidth) || (size.height != mCameraParameter.mPictureHeight)) {
			param.setPictureSize(mCameraParameter.mPictureWidth, mCameraParameter.mPictureHeight);
			change = true;
		}

		String wb = param.getWhiteBalance();
		if (!wb.equals(mCameraParameter.whiteBalance)) {
			param.setWhiteBalance(mCameraParameter.whiteBalance);
			change = true;
		}

		if (change) {
			mCamera.setParameters(param);
		}
	}

	public synchronized void setSaveDirectory(String aSaveDirectory) {
		if ((mSaveDirectory != null) && (mSaveDirectory.equals(aSaveDirectory))) {
			return;
		}
		mSaveDirectory = aSaveDirectory;
	}

	public synchronized void setPeriod(long aPeriod) {
		if (mPeriod == aPeriod) {
			return;
		}
		mPeriod = aPeriod;

		if (mCameraState == CameraState.WAIT) {
			long nextShot = calNextShot();
			if (nextShot != mNextShot) {
				mNextTask.cancel();
				mNextTask = null;
				beginWait();
			}
		}
	}

	public synchronized long calNextShot() {
		// return ((System.currentTimeMillis() / mPeriod) + 1) * mPeriod;
		return (((mTimeManager.currentRealTime() + 1000) / mPeriod) + 1) * mPeriod;
		// 1000: torrent of NTP float
	}

	public synchronized void killCamera() {
		if (mCamera != null) {
			mCamera.stopPreview();
			mCamera.release();
			mCamera = null;
		}
		mCameraState = CameraState.NULL;
	}

	private synchronized void nextStateTask(CameraState aState, TimerTask aTimerTask, long aDelay) {
		if (aDelay < 0)
			aDelay = 0;
		mNextTask = aTimerTask;
		mTimer.schedule(mNextTask, aDelay);
		mCameraState = aState;

	}

}
