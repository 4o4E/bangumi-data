package top.e404.bangumi.server.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CharacterGenderScopeTest {
    @Test
    fun `明确女性身体说明优先于上游派生的单值性别`() {
        assertEquals(
            CharacterGenderScope.FEMALE,
            parseCharacterGenderScope("male", listOf("生物学上的女")),
        )
        assertEquals(CharacterGenderScope.FEMALE, parseCharacterGenderScope("female", emptyList()))
        assertEquals(CharacterGenderScope.MALE, parseCharacterGenderScope("male", emptyList()))
    }

    @Test
    fun `固定性转按变化后身体分类而不是判断是否包含女字`() {
        assertEquals(CharacterGenderScope.FEMALE, parseCharacterGenderScope(null, listOf("男→女（性醒流转后）")))
        assertEquals(CharacterGenderScope.MALE, parseCharacterGenderScope(null, listOf("女 -> 男")))
        assertEquals(CharacterGenderScope.FEMALE, parseCharacterGenderScope(null, listOf("女（原男）")))
    }

    @Test
    fun `条件性男女身体转换同时具有两种资格`() {
        assertEquals(CharacterGenderScope.BOTH, parseCharacterGenderScope("male", listOf("男（遇到冷水变成女孩）")))
        assertEquals(CharacterGenderScope.BOTH, parseCharacterGenderScope("female", listOf("女（可以变成男性）")))
        assertEquals(CharacterGenderScope.BOTH, parseCharacterGenderScope(null, listOf("男女切换")))
    }

    @Test
    fun `穿着说明不改变身体性别且未识别的资料保持未知`() {
        assertEquals(CharacterGenderScope.MALE, parseCharacterGenderScope(null, listOf("男（女装）")))
        assertEquals(CharacterGenderScope.MALE, parseCharacterGenderScope("male", listOf("女装")))
        assertNull(parseCharacterGenderScope(null, listOf("男或女")))
        assertNull(parseCharacterGenderScope(null, emptyList()))
    }
}
