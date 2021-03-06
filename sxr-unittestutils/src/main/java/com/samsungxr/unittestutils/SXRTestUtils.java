/*
 * Copyright (c) 2016. Samsung Electronics Co., LTD
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

package com.samsungxr.unittestutils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.mtp.MtpConstants;
import android.os.Build;
import android.os.Environment;

import net.jodah.concurrentunit.Waiter;

import com.samsungxr.SXRContext;
import com.samsungxr.SXRMain;
import com.samsungxr.SXRScene;
import com.samsungxr.SXRNode;
import com.samsungxr.SXRScreenshotCallback;
import com.samsungxr.utility.Log;
import com.samsungxr.utility.Threads;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;


/**
 * This class defines utility function to be used for writing unit tests for GearVR framework
 */
public class SXRTestUtils implements SXRMainMonitor {
    private static final String TAG = SXRTestUtils.class.getSimpleName();
    private static final String DEFAULT_DEVICE_TYPE = "S7Edge";
    public static final String DEVICE_TYPE = GetDeviceType();
    public static final String GITHUB_URL = "https://raw.githubusercontent.com/samsungxr/GearVRf-Tests/master/";
    private static final String GOLDEN_MASTERS_BASE_URL = GITHUB_URL + "golden_masters/";
    public static final String GOLDEN_MASTERS_URL = GOLDEN_MASTERS_BASE_URL + DEVICE_TYPE;

    protected static final int SCREENSHOT_TEST_TIMEOUT = 80000;

    private SXRContext sxrContext;
    private final CountDownLatch onInitLatch = new CountDownLatch(1);
    private final CountDownLatch onStepLatch = new CountDownLatch(1);
    private final Object onScreenshotLock;
    private final Object xFramesLock;
    private boolean mFramesLockDone = true;
    private final Object onAssetLock;
    private SXRTestableMain testableMain;
    private SXRScene mainScene;
    private OnInitCallback onInitCallback;
    private OnRenderCallback onRenderCallback;
    private boolean mAssetIsLoaded = false;

    /**
     * Constructor, needs an instance of {@link SXRTestableActivity}.
     * @param testableSXRActivity The instance of the activity to be tested.
     */
    public SXRTestUtils(SXRTestableActivity testableSXRActivity) {
        this(testableSXRActivity, null);
    }

    /**
     * Constructor, needs an instance of {@link SXRTestableActivity} && SXRTestUtils.OnInitCallback.
     * @param testableSXRActivity The instance of the activity to be tested.
     */
    public SXRTestUtils(SXRTestableActivity testableSXRActivity, OnInitCallback onInitCallback) {
        sxrContext = null;
        xFramesLock = new Object();
        onScreenshotLock = new Object();
        onAssetLock = new Object();
        this.onInitCallback = onInitCallback;

        if (testableSXRActivity == null) {
            throw new IllegalArgumentException();
        }

        testableMain = testableSXRActivity.getSXRTestableMain();
        if (testableMain != null) {
            testableMain.setMainMonitor(this);
        }

    }

    private static String GetDeviceType() {
        String TryUrl = GOLDEN_MASTERS_BASE_URL + Build.MODEL;
        try {
            return new BufferedReader(new InputStreamReader(new URL(TryUrl).openStream())).readLine().trim();
        } catch (Exception ex) {
            Log.e(TAG,"Golden master redirect not found: " + TryUrl);
            return DEFAULT_DEVICE_TYPE;
        }
    }

    /**
     * Waits for the {@link SXRMain#onInit(SXRContext)} to be called on the corresponding
     * {@link SXRMain}. This function is useful to obtain an instance to the {@link SXRContext}
     * in the unit tests.
     * @return Returns the {@link SXRContext} instance associated with the application.
     */
    public SXRContext waitForOnInit() {
        if (sxrContext == null) {
            if (testableMain.isOnInitCalled()) {
                sxrContext = testableMain.getSXRContext();
                mainScene = sxrContext.getMainScene();
                return sxrContext;
            }
            try {
                Log.d(TAG, "Waiting for OnInit");
                onInitLatch.await();
            } catch (InterruptedException e) {
                Log.e(TAG, "", e);
                Thread.currentThread().interrupt();
                return null;
            }
            return sxrContext;
        } else {
            return sxrContext;
        }
    }

    /**
     * Waits for the first frame to be rendered. It returns after the first time
     * {@link SXRMain#onStep()} is called. If {@link SXRMain#onStep()} is already called it
     * returns immediately. This is a blocking call.
     */
    public void waitForSceneRendering() {
        if (testableMain.isSceneRendered()) {
            return;
        }

        try {
            Log.d(TAG, "Waiting for OnStep");
            onStepLatch.await();
        } catch (InterruptedException e) {
            Log.e(TAG, "", e);
            Thread.currentThread().interrupt();
            return;
        }
    }

    public void waitForAssetLoad() {
        synchronized (onAssetLock) {
            while(!mAssetIsLoaded) {
                try {
                    Log.d(TAG, "Waiting for OnAssetLoaded");
                    onAssetLock.wait();
                } catch (InterruptedException e) {
                    Log.e(TAG, "", e);
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            mAssetIsLoaded = false;
        }
    }

    /**
     * Waits for "frames" number of frames to be rendered before returning. This is a blocking call.
     * @param frames number of frames to wait for
     */
    public void waitForXFrames(int frames) {
        synchronized (xFramesLock) {
            mFramesLockDone = false;
            testableMain.notifyAfterXFrames(frames);
            while(!mFramesLockDone) {
                try {
                    xFramesLock.wait();
                } catch (InterruptedException e) {
                    Log.e(TAG,"",e);
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void onInitCalled(SXRContext context, SXRScene mainScene) {
        this.mainScene = mainScene;
        sxrContext = context;
        if (onInitCallback != null) {
            onInitCallback.onInit(sxrContext);
        }
        onInitLatch.countDown();
        Log.d(TAG, "On Init called");
    }

    @Override
    public void onSceneRendered() {
        if (onRenderCallback != null) {
            onRenderCallback.onSceneRendered();
        }
        onStepLatch.countDown();
    }

    public void xFramesRendered() {
        synchronized (xFramesLock) {
            mFramesLockDone = true;
            xFramesLock.notifyAll();
        }
    }

    public void onAssetLoaded(SXRNode asset) {
        synchronized (onAssetLock) {
            mAssetIsLoaded = true;
            onAssetLock.notifyAll();
        }
        Log.d(TAG, "OnAssetLoaded Called");
    }

    /**
     *  Returns the {@link SXRContext} associated with the application
     * @return the {@link SXRContext} instance
     */
    public SXRContext getSxrContext() {
        return sxrContext;
    }

    /**
     *  Returns the main scene to be rendered.
     * @return the {@link SXRScene} instance of the main scene.
     */
    public SXRScene getMainScene() {
        return mainScene;
    }

    /**
     * Set a callback to be executed in the {@link SXRMain#onInit(SXRContext)} method. This can
     * be used for executing code in the {@link SXRMain#onInit(SXRContext)} method. To use
     * assertions inside the {@link OnInitCallback} use the {@link Waiter} class.
     * @param callback
     */
    public void setOnInitCallback(OnInitCallback callback) {
        this.onInitCallback = callback;
    }

    /**
     * Set a callback to be executed when {@link SXRMain#onStep()} is called for the first time.
     * This callback executed on the GL thread. Use this to execute code in the
     * {@link SXRMain#onStep()} method. To use assertions inside the {@link OnRenderCallback} use
     * the {@link Waiter} class.
     * @param callback
     */
    public void setOnRenderCallback(OnRenderCallback callback) { this.onRenderCallback = callback; }

    /**
     * Defines the interface for setting a callback in the {@link SXRMain#onInit(SXRContext)}
     * method. Use the {@link SXRTestUtils#setOnInitCallback(OnInitCallback)} to set this callback.
     */
    public interface OnInitCallback {
        void onInit(SXRContext sxrContext);
    }

    /**
     * Defines the interface for setting a callback which is invoked when the
     * {@link SXRMain#onStep()} is executed for the first time. Use the
     * {@link SXRTestUtils#setOnRenderCallback(OnRenderCallback)} to set this callback.
     */
    public interface OnRenderCallback {
        void onSceneRendered();
    }

    public interface OnAssetCallback {
        void onAssetLoaded(SXRNode asset);
    }

    /**
     * Class which captures a screenshot and compares it with a golden screenshot from the
     * assets directory. This method looks for a file named "diff_$testname$.png" in the assets
     * folder for the reference screenshot of the expected result. The captured screenshots are
     * stored in /sdcard/GearVRfTests/$category$/$testname$.png
     */
    class ScreenShooter implements SXRScreenshotCallback
    {
        private Waiter mWaiter;
        private String mTestName;
        private String mCategory;
        private boolean mDoCompare;

        /**
         * Prepare for a new screen capture.
         * @param category directory to store screenshots in.
         * @param testname the name of the test method.
         * @param waiter instance of the {@link Waiter} class.
         * @param compare flag used to turnon/off comparison of screenshots.
         */
        public void init(String testname, String category, Waiter waiter, boolean compare)
        {
            mTestName = testname;
            mCategory = category;
            mWaiter = waiter;
            mDoCompare = compare;
        }

        private void compareWithGolden(final Bitmap screenshot)
        {
            try
            {
                Bitmap golden = null;
                String testname = mTestName + ".png";

                try
                {
                    URL url = new URL(GOLDEN_MASTERS_URL + "/" + mCategory + "/" + testname);
                    Log.v(TAG, "Fetching golden master " + url.toString());
                    final InputStream inputStream = url.openStream();
                    try
                    {
                        final BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inMutable = true;
                        golden = BitmapFactory.decodeStream(inputStream, null, options);
                    }
                    finally
                    {
                        inputStream.close();
                    }
                }
                catch (Throwable ex)
                {
                    mWaiter.fail(ex);
                }
                if (golden != null)
                {
                    try
                    {
                        final float[] diff = {0.0f};

                        final int goldenHeight = golden.getHeight();
                        final int goldenWidth = golden.getWidth();

                        mWaiter.assertEquals(goldenWidth, screenshot.getWidth());
                        mWaiter.assertEquals(goldenHeight, screenshot.getHeight());

                        final ReentrantLock lockDiff = new ReentrantLock();

                        try
                        {
                            final CountDownLatch cdl = new CountDownLatch(goldenHeight);

                            final int[] goldenPixels = new int[goldenHeight * goldenWidth];
                            golden.getPixels(goldenPixels, 0, goldenWidth, 0, 0, goldenWidth,
                                             goldenHeight);

                            final int[] screenshotPixels = new int[goldenHeight * goldenWidth];
                            screenshot.getPixels(screenshotPixels, 0, goldenWidth, 0, 0,
                                                 goldenWidth, goldenHeight);

                            for (int y = 0; y < goldenHeight; y++)
                            {
                                Threads.spawn(
                                        new CompareRunnable(goldenWidth, y, goldenPixels, lockDiff,
                                                            diff, screenshotPixels, cdl));
                            }

                            cdl.await();
                            golden.setPixels(goldenPixels, 0, goldenWidth, 0, 0, goldenWidth,
                                             goldenHeight);
                            //hints
                            System.gc();
                            System.runFinalization();
                        }
                        catch (Throwable t)
                        {
                            mWaiter.fail(t);
                        }

                        Log.e(mCategory, "RESULT: %s %s diff = %f", mCategory, mTestName, diff[0]);
                        if (diff[0] > 2000.0f)
                        {
                            writeBitmap(mCategory, "diff_" + testname, golden);
                        }

                        mWaiter.assertTrue(diff[0] <= 30000.0f);
                    }
                    finally
                    {
                        golden.recycle();
                    }
                }
            }
            finally
            {
                screenshot.recycle();
            }
        }

        protected void writeBitmap(String dir, String filename, Bitmap bitmap)
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            File sdcard = Environment.getExternalStorageDirectory();
            dir = sdcard.getAbsolutePath() + "/GearVRFTests/" + dir + "/";
            File d = new File(dir);
            d.mkdirs();

            try
            {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
                FileOutputStream fo = new FileOutputStream(new File(d, filename));
                try {
                    bytes.writeTo(fo);
                } finally {
                    fo.close();
                }
            }
            catch (Throwable ex)
            {
                ex.printStackTrace();
                mWaiter.fail(ex);
            } finally {
                try {
                    bytes.close();
                } catch (IOException e) {
                }
            }
        }

        @Override
        public void onScreenCaptured(Bitmap bitmap)
        {
            synchronized (onScreenshotLock)
            {
                String basename = mTestName + ".png";

                try
                {
                    writeBitmap(mCategory, basename, bitmap);
                }
                catch (Exception e)
                {
                    Log.e(mCategory, "Could not save screenshot of %s", mTestName);
                    mWaiter.fail(e);
                }
                try
                {
                    Log.d(mCategory, "Saved screenshot of %s", mTestName);
                    if (mDoCompare)
                    {
                        compareWithGolden(bitmap);
                    }
                }
                catch (Throwable t)
                {
                    Log.d(mCategory, "Exception while comparing screenshot for %s", mTestName);
                    mWaiter.fail(t);
                }
                mWaiter.resume();
            }
        }
    };

    ScreenShooter mScreenShooter = new ScreenShooter();

    /**
     * Captures a center screenshot and compares it with a golden screenshot from the
     * assets directory. This method looks for a file named "diff_$testname$.png" in the assets
     * folder for the reference screenshot of the expected result. The captured screenshots are
     * stored in /sdcard/GearVRfTests/$category$/$testname$.png
     * @param category directory to store screenshots in.
     * @param testname the name of the test method.
     * @param waiter instance of the {@link Waiter} class.
     * @param doCompare flag used to turnon/off comparison of screenshots.
     * @throws TimeoutException
     */
    public void screenShot(final String category, final String testname, final Waiter waiter,
                           final boolean doCompare) throws TimeoutException
    {
        mScreenShooter.init(testname, category, waiter, doCompare);
        waitForSceneRendering();
        sxrContext.captureScreenCenter(mScreenShooter);
        waiter.await(SCREENSHOT_TEST_TIMEOUT);
    }

    /**
     * Captures a right screenshot and compares it with a golden screenshot from the
     * assets directory. This method looks for a file named "diff_$testname$.png" in the assets
     * folder for the reference screenshot of the expected result. The captured screenshots are
     * stored in /sdcard/GearVRfTests/$category$/$testname$.png
     * @param category directory to store screenshots in.
     * @param testname the name of the test method.
     * @param waiter instance of the {@link Waiter} class.
     * @param doCompare flag used to turnon/off comparison of screenshots.
     * @throws TimeoutException
     */
    public void screenShotRight(final String category, final String testname, final Waiter waiter,
                           final boolean doCompare) throws TimeoutException
    {
        mScreenShooter.init(testname, category, waiter, doCompare);
        waitForSceneRendering();
        sxrContext.captureScreenRight(mScreenShooter);
        waiter.await(SCREENSHOT_TEST_TIMEOUT);
    }

    final static class CompareRunnable implements Runnable {
        private final int y;
        private int[] goldenPixels;
        private ReentrantLock lockDiff;
        private float[] diff;
        private int[] screenshotPixels;
        private CountDownLatch cdl;
        private final int goldenWidth;

        CompareRunnable(final int goldenWidth, final int y, final int[] goldenPixels, final ReentrantLock lockDiff, final float[] diff,
                        final int[] screenshotPixels, final CountDownLatch cdl) {
            this.y = y;
            this.goldenPixels = goldenPixels;
            this.lockDiff = lockDiff;
            this.diff = diff;
            this.screenshotPixels = screenshotPixels;
            this.cdl = cdl;
            this.goldenWidth = goldenWidth;
        }

        @Override
        public void run() {
            try {
                for (int x = 0; x < goldenWidth; x++) {
                    int p1 = goldenPixels[x*goldenWidth + y];
                    int p2 = screenshotPixels[x*goldenWidth + y];
                    int r = Math.abs(Color.red(p1) - Color.red(p2));
                    int g = Math.abs(Color.green(p1) - Color.green(p2));
                    int b = Math.abs(Color.blue(p1) - Color.blue(p2));
                    goldenPixels[x*goldenWidth + y] = Color.argb(255, r, g, b);

                    lockDiff.lock();
                    try {
                        diff[0] += (float) r / 255.0f + g / 255.0f + b / 255.0f;
                    } finally {
                        lockDiff.unlock();
                    }
                }
            } finally {
                cdl.countDown();

                goldenPixels = null;
                screenshotPixels = null;
                lockDiff = null;
                cdl = null;
                diff = null;
            }
        }
    }
}
