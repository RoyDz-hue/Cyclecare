package com.cyclecare.app.ui

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.cyclecare.app.LocalReadFailureContent
import com.cyclecare.app.ui.theme.CycleCareTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalReadFailureContrastTest {
    @get:Rule val compose=createComposeRule()
    private fun render(dark:Boolean){
        var retries=0
        compose.setContent { CycleCareTheme(darkTheme=dark,dynamicColor=false){LocalReadFailureContent{retries++}} }
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1,retries)
        val image=compose.onRoot().captureToImage().asAndroidBitmap()
        val target=File(System.getProperty("stageg.evidence.dir","build/stageg-pixels"),if(dark)"failure-dark.png" else "failure-light.png")
        target.parentFile.mkdirs()
        target.outputStream().use{assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
        assertTrue(target.length()>0)
    }
    @Test fun darkFailureTextAndRetry(){render(true)}
    @Test fun lightFailureTextAndRetry(){render(false)}
}
