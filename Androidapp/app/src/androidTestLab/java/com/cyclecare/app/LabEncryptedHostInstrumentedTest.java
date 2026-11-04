package com.cyclecare.app;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.cyclecare.app.lab.store.LabEncryptedHostProof;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.assertTrue;
/** Explicit class filter only. No Activity, MainActivity, Intent or app UI launch. */
@RunWith(AndroidJUnit4.class)
public class LabEncryptedHostInstrumentedTest {
 private String asset(String name) throws Exception {
  try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("encrypted-host/"+name);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
   byte[] buffer=new byte[4096];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
   return new String(out.toByteArray(),StandardCharsets.UTF_8);
  }
 }
 @Test public void syntheticEncryptedProtocol() throws Exception {
  String result=LabEncryptedHostProof.run(InstrumentationRegistry.getInstrumentation().getTargetContext(),asset("track-plans.json"),asset("settings-oracle.json"));
  assertTrue("Synthetic evidence is preserved privately on failure",result.equals("PASS"));
 }
}
