package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProvidersTest {
    @Test fun openRouterOnlyUsesFreeModelsThatTakeImages() {
        val body = """{"data":[
            {"id":"openai/gpt-5","architecture":{"input_modalities":["text","image"]},"pricing":{"prompt":"0.00001","completion":"0.00003"}},
            {"id":"google/gemma-3-27b-it:free","architecture":{"input_modalities":["text","image"]},"pricing":{"prompt":"0","completion":"0"}},
            {"id":"deepseek/deepseek-chat:free","architecture":{"input_modalities":["text"]},"pricing":{"prompt":"0","completion":"0"}}
        ]}"""
        val models = ModelPicker.parseModels(body)
        val vision = ModelPicker.compatVisionCandidates(models, Settings.OPENROUTER_BASE)
        assertEquals("google/gemma-3-27b-it:free", vision.first())
        assertFalse(vision.contains("openai/gpt-5"))
        assertFalse(vision.contains("deepseek/deepseek-chat:free"))
        assertEquals("deepseek/deepseek-chat:free", ModelPicker.pickCompatText(models, Settings.OPENROUTER_BASE))
    }

    @Test fun mistralVisionFlagAndGroqQwenAreRecognized() {
        val mistral = ModelPicker.parseModels(
            """{"data":[{"id":"codestral-latest","capabilities":{"vision":false}},{"id":"mistral-small-latest","capabilities":{"vision":true}}]}"""
        )
        assertEquals("mistral-small-latest", ModelPicker.compatVisionCandidates(mistral, Settings.MISTRAL_BASE).first())
        assertEquals("mistral-small-latest", ModelPicker.pickCompatText(mistral, Settings.MISTRAL_BASE))
        val groq = ModelPicker.parseModels("""{"data":[{"id":"openai/gpt-oss-120b"},{"id":"qwen/qwen3.6-27b"},{"id":"whisper-large-v3"}]}""")
        assertTrue(ModelPicker.compatVisionCandidates(groq, Settings.DEFAULT_COMPAT_BASE).contains("qwen/qwen3.6-27b"))
    }

    @Test fun automaticOrderFollowsTheTest() {
        val t = mapOf(
            "gemini" to AiClient.TestResult(false, false, 0.0, "503", null, 1L),
            "compat" to AiClient.TestResult(false, true, 1.0, "não leu", null, 1L),
            "mistral" to AiClient.TestResult(true, true, 4.0, null, null, 1L),
            "openrouter" to AiClient.TestResult(true, true, 2.5, null, null, 1L)
        )
        val have = listOf("gemini", "compat", "mistral", "openrouter")
        assertEquals(listOf("openrouter", "mistral", "compat", "gemini"), AiClient.autoOrder(have, t, true))
        assertEquals(listOf("compat", "openrouter", "mistral", "gemini"), AiClient.autoOrder(have, t, false))
    }
}
