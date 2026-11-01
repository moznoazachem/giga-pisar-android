package ru.gigapisar.text

import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleSyntaxTest {
    @Test
    fun oneSentenceLosesCapitalAndPeriod() {
        assertEquals("привет, как дела", SimpleSyntax.simplified("Привет, как дела."))
        assertEquals("буду через пять минут", SimpleSyntax.simplified(" Буду через пять минут. "))
        assertEquals("ok", SimpleSyntax.simplified("Ok."))
    }

    @Test
    fun questionAndExclamationKeepTheirMark() {
        assertEquals("ты где?", SimpleSyntax.simplified("Ты где?"))
        assertEquals("отлично!", SimpleSyntax.simplified("Отлично!"))
    }

    @Test
    fun severalSentencesStayAsTheyAre() {
        assertEquals("Привет. Как дела?", SimpleSyntax.simplified("Привет. Как дела?"))
        assertEquals("Жду тебя в пять; не опаздывай.", SimpleSyntax.simplified("Жду тебя в пять; не опаздывай."))
        assertEquals("Купить: хлеб, т.д.", SimpleSyntax.simplified("Купить: хлеб, т.д."))
        assertEquals("Первая\nВторая.", SimpleSyntax.simplified("Первая\nВторая."))
    }

    @Test
    fun abbreviationsAndNamesAreNotLowercased() {
        assertEquals("СБЕР открыл отделение", SimpleSyntax.simplified("СБЕР открыл отделение."))
        // Only a word in full capitals is spared, as on the Mac: a mixed-case name loses its capital.
        assertEquals("gigaAM распознаёт быстро", SimpleSyntax.simplified("GigaAM распознаёт быстро."))
        assertEquals("а", SimpleSyntax.simplified("А."))
    }

    @Test
    fun emptyAndAlreadySimpleTextIsUntouched() {
        assertEquals("", SimpleSyntax.simplified(""))
        assertEquals("   ", SimpleSyntax.simplified("   "))
        assertEquals("уже просто", SimpleSyntax.simplified("уже просто"))
    }
}
