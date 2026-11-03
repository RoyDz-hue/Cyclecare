package com.cyclecare.app;

import android.Manifest;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.cyclecare.app.lab.RustCalendarLabActivity;
import com.cyclecare.app.lab.RustCalendarLabProof;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.File;
import java.io.FileOutputStream;

/** Explicit class filter only. Java avoids app-provided Kotlin helper ABI.
 * Only lab-kept entry methods returning primitive/String cross APK boundary. */
@RunWith(AndroidJUnit4.class)
public class RustCalendarLabInstrumentedTest {
    private interface Condition { boolean ready(); }
    private void await(Condition condition) throws Exception {
        long deadline = System.nanoTime() + 30000000000L;
        while (!condition.ready() && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue("Synthetic native calendar state did not become ready", condition.ready());
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String description) {
        if (node == null) return null;
        CharSequence value = node.getContentDescription();
        if (value != null && (value.toString().equals(description) || value.toString().startsWith(description + ","))) return node;
        for (int i=0; i<node.getChildCount(); i++) {
            AccessibilityNodeInfo hit = find(node.getChild(i), description);
            if (hit != null) return hit;
        }
        return null;
    }
    @SuppressWarnings("deprecation")
    private void assertIsolatedProcess() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET));
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(),
            PackageManager.GET_PROVIDERS | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS);
        if (info.providers != null) for (android.content.pm.ProviderInfo p: info.providers)
            assertFalse(p.name.equals("androidx.startup.InitializationProvider"));
        if (info.services != null) for (android.content.pm.ServiceInfo p: info.services) assertFalse(p.name.startsWith("androidx.work."));
        if (info.receivers != null) for (android.content.pm.ActivityInfo p: info.receivers) assertFalse(p.name.startsWith("androidx.work."));
        String[] names = new File(context.getApplicationInfo().dataDir, "databases").list();
        if (names != null) for (String n: names) assertFalse("Unexpected health/WorkManager DB",
            n.startsWith("cycle_care_database") || n.startsWith("cycle_care_owner_") || n.startsWith("androidx.work.workdb"));
    }
    @Test public void nativeMonthMatchesExistingKotlinPredictorForSyntheticEdges() {
        assertEquals("", RustCalendarLabProof.runOracleChecks());
    }
    @Test public void existingCalendarRendersNativeAndRejectsSupersededFixture() throws Exception {
        final android.app.Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        Context context = inst.getTargetContext();
        assertEquals("com.cyclecare.app.lab", context.getPackageName());
        assertIsolatedProcess();
        final RustCalendarLabActivity activity = (RustCalendarLabActivity) inst.startActivitySync(
            new Intent(context, RustCalendarLabActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            await(new Condition() { public boolean ready() { return activity.nativeReady(); } });
            assertEquals("", activity.checkInitialMonth());
            await(new Condition() { public boolean ready() { return find(inst.getUiAutomation().getRootInActiveWindow(), "Next month") != null; } });
            Bitmap screenshot = inst.getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);
            FileOutputStream stream = new FileOutputStream(new File(context.getExternalFilesDir(null), "synthetic-calendar-native.png"));
            try { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)); } finally { stream.close(); }
            assertTrue(find(inst.getUiAutomation().getRootInActiveWindow(), "Next month").performAction(AccessibilityNodeInfo.ACTION_CLICK));
            await(new Condition() { public boolean ready() { return activity.nativeReady() && activity.monthValue() == 3; } });
            AccessibilityNodeInfo first = find(inst.getUiAutomation().getRootInActiveWindow(), "March 1");
            assertNotNull("Calendar needs a non-color accessibility cue", first);
            assertTrue(first.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            await(new Condition() { public boolean ready() { return activity.selectedMarchFirst(); } });
            final boolean[] captured = new boolean[1];
            inst.runOnMainSync(new Runnable() { public void run() { captured[0] = activity.captureMarchAndSupersede(); } });
            assertTrue("Current March payload must be captured before supersession", captured[0]);
            await(new Condition() { public boolean ready() { return activity.nativeReady() && activity.epochValue() == 2L; } });
            assertEquals("", activity.checkEpochOnlyRejection());
            assertIsolatedProcess();
        } finally { inst.runOnMainSync(new Runnable() { public void run() { activity.finish(); } }); }
    }
}
