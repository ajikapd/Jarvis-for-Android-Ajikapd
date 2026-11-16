package com.jarvis.core.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteParserTest {
    private val buy = NoteParser.SHOPPING

    @Test fun addToShopping() = assertEquals(NoteCommand.Add(buy, listOf("молоко")), NoteParser.parse("запиши в покупки молоко"))
    @Test fun addTail() = assertEquals(NoteCommand.Add(buy, listOf("молоко")), NoteParser.parse("добавь молоко в список покупок"))
    @Test fun several() = assertEquals(NoteCommand.Add(buy, listOf("хлеб", "сыр", "чай")), NoteParser.parse("добавь в покупки хлеб, сыр и чай"))
    @Test fun read() = assertEquals(NoteCommand.Read(buy), NoteParser.parse("что в списке покупок"))
    @Test fun readShort() = assertEquals(NoteCommand.Read(buy), NoteParser.parse("прочитай покупки"))
    @Test fun remove() = assertEquals(NoteCommand.Remove(buy, "молоко"), NoteParser.parse("вычеркни молоко из покупок"))
    @Test fun clear() = assertEquals(NoteCommand.Clear(buy), NoteParser.parse("очисти список покупок"))
    @Test fun todo() = assertEquals(NoteCommand.Add(NoteParser.TODO, listOf("позвонить врачу")), NoteParser.parse("добавь в список дел позвонить врачу"))
    @Test fun thought() = assertEquals(NoteCommand.Add(NoteParser.NOTES, listOf("купить дачу у озера")), NoteParser.parse("запиши мысль купить дачу у озера"))
    @Test fun note() = assertEquals(NoteCommand.Add(NoteParser.NOTES, listOf("код от домофона 45")), NoteParser.parse("запиши в заметки код от домофона 45"))
    @Test fun readNotes() = assertEquals(NoteCommand.Read(NoteParser.NOTES), NoteParser.parse("какие у меня заметки"))
    @Test fun unrelated() = assertNull(NoteParser.parse("открой телеграм"))
    @Test fun addWithoutItem() = assertNull(NoteParser.parse("добавь в покупки"))

    @Test fun speech() {
        assertEquals("В покупках: хлеб, сыр.", NoteSpeech.read(buy, listOf("хлеб", "сыр")))
        assertEquals("Список покупок пуст, сэр.", NoteSpeech.read(buy, emptyList()))
    }
}
