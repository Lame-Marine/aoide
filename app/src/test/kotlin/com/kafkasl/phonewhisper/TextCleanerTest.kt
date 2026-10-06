package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleanerTest {
    private val fill = TextCleaner.parseFillers(TextCleaner.DEFAULT_FILLERS)
    private fun clean(s: String, caps: Boolean = true, spoken: Boolean = false, fillers: List<String> = fill) =
        TextCleaner.apply(s, TextCleaner.Options(fillers, caps, spoken))

    @Test fun removesLeadingFiller() = assertEquals("So I went home.", clean("Um, so I went home."))
    @Test fun removesMidFiller() = assertEquals("I went to the store.", clean("I went um to the store."))
    @Test fun removesCommaWrappedFiller() = assertEquals("I went, to the store.", clean("I went, um, to the store."))
    @Test fun removesTrailingFiller() = assertEquals("I went home.", clean("I went home, uh."))
    @Test fun keepsWordsContainingFiller() = assertEquals("The hummus is here.", clean("the hummus is here."))
    @Test fun keepsUmbrella() = assertEquals("My umbrella broke.", clean("my umbrella broke."))
    @Test fun fillersOffKeepsThem() = assertEquals("Um, so yes.", clean("um, so yes.", fillers = emptyList()))
    @Test fun capitalisesSentencesAndI() = assertEquals("Hello there. I think i.e. so. Yes!", clean("hello there. i think i.e. so. yes!"))
    @Test fun fixesContractionI() = assertEquals("I'm sure I'll go.", clean("i'm sure i'll go."))
    @Test fun capsOff() = assertEquals("hello there", clean("hello there", caps = false))
    @Test fun stripsBlankAudio() = assertEquals("Hello.", clean("[BLANK_AUDIO] hello."))
    @Test fun stripsMusicTag() = assertEquals("Hello.", clean("[ Music ] hello."))
    @Test fun spokenPunctuation() =
        assertEquals("Hello, world. Are you there?", clean("hello comma world period are you there question mark", spoken = true))
    @Test fun spokenNewLine() =
        assertEquals("First line\nSecond line", clean("first line new line second line", spoken = true))
    @Test fun spokenNewParagraph() =
        assertEquals("One.\n\nTwo.", clean("one period new paragraph two period", spoken = true))
    @Test fun spokenOffLeavesWords() =
        assertEquals("The period of time, comma.", clean("the period of time, comma.", spoken = false))
    @Test fun parseFillersTrimsAndDedupes() =
        assertEquals(listOf("um", "uh"), TextCleaner.parseFillers(" Um , uh,, UM "))
}
