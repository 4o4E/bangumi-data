package top.e404.bangumi.server.catalog

import java.text.Normalizer
import java.util.Locale

/** 内部记录身体形态对应的查询资格；BOTH 不进入公开 API 的单次查询性别枚举。 */
enum class CharacterGenderScope {
    FEMALE,
    MALE,
    BOTH,
}

/**
 * 资料中的身体变化说明比上游派生的单值 gender 更完整，优先解析资料，未识别时再使用标准值。
 * 固定性转按变化后的身体归类；带条件的男女身体转换同时具有两种资格，女装不视为性转。
 */
internal fun parseCharacterGenderScope(standard: String?, descriptions: List<String>): CharacterGenderScope? {
    for (description in descriptions) {
        parseGenderDescription(description)?.let { return it }
    }
    return standard?.let(::parseGenderDescription)
}

private fun parseGenderDescription(value: String): CharacterGenderScope? {
    val text = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filterNot(Char::isWhitespace)
    val conditional = CONDITIONAL_CHANGE.matchEntire(text)
    if (conditional != null) {
        val original = genderLiteral(conditional.groupValues[1])
        val transformed = genderLiteral(conditional.groupValues[2])
        return if (original != transformed) CharacterGenderScope.BOTH else original
    }
    if (text == "男女切换" || text == "男女可切换") {
        return CharacterGenderScope.BOTH
    }
    BIOLOGICAL_GENDER.matchEntire(text)?.let { return genderLiteral(it.groupValues[1]) }
    val body = text.substringBefore('(')
    FIXED_CHANGE.matchEntire(body)?.let { return genderLiteral(it.groupValues[1]) }
    return genderLiteral(body)
}

private fun genderLiteral(value: String): CharacterGenderScope? {
    return when (value) {
        "female", "女", "女性", "女孩", "女生" -> CharacterGenderScope.FEMALE
        "male", "男", "男性", "男孩", "男生" -> CharacterGenderScope.MALE
        else -> null
    }
}

private val CONDITIONAL_CHANGE = Regex(
    "^(男性|女性|男|女)\\((?:遇到|遇|接触|碰到|淋到|可以|可在|可|能|会|受到).*(?:变成|变为|变身为|变身成)(女性|男性|女孩|男孩|女生|男生|女|男).*\\)$",
)
private val FIXED_CHANGE = Regex("^(?:男性|女性|男|女)(?:→|->|变成|变为)(女性|男性|女孩|男孩|女生|男生|女|男)$")
private val BIOLOGICAL_GENDER = Regex("^(?:生物学|生理|身体)(?:上)?(?:的)?(?:性别[:：]?)?(女性|男性|女|男)$")
