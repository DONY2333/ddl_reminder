package com.example.ddlreminder.smartadd

enum class FastPath { LOCAL, AI }

/** 只做保守语法分类，不生成日期/标题。任何不确定文本交给现有 AI pipeline。 */
object FastPathDetector {
    private val date = "(?:今天|明天|后天|(?:下周|这周|本周|周)[一二三四五六日天]|(?:\\d{4}年)?\\d{1,2}月\\d{1,2}[日号])"
    private val time = "(?:(?:凌晨|早上|上午|中午|下午|晚上)?(?:\\d{1,2}:\\d{2}|[零〇一二两三四五六七八九十\\d]{1,3}点(?:半|[零〇一二两三四五六七八九十\\d]{1,2}分)?))"
    private val datePattern = Regex(date)
    private val timePattern = Regex(time)
    private val simple = Regex("^$date(?:$time)?[\\p{L}0-9]{1,18}$")
    private val complex = Regex("取消|改期|改到|改为|延期|推迟|暂定|拟定|预计|通知|公告|所有人|原定|另行|分别|然后|以及|同时|另外|根据|上述|这个|那个|他说|老师说|地点|闭卷|开卷|时长|题型|携带|要求|提前|小时|分钟|和|与|或|之前|之后|两场|两次|多个")
    private val event = Regex("考试|体测|测验|班会|会议|组会|开会|汇报|答辩|比赛|竞赛|生日|报名|作业|论文|报告|材料|申请|pre", RegexOption.IGNORE_CASE)

    fun detect(text: String): FastPath {
        val value = text.trim()
        if (!simple.matches(value) || complex.containsMatchIn(value)) return FastPath.AI
        if (Regex("凌晨|早上|上午|中午|下午|晚上").containsMatchIn(value) && !timePattern.containsMatchIn(value)) return FastPath.AI
        if (datePattern.findAll(value).count() != 1 || timePattern.findAll(value).count() > 1) return FastPath.AI
        if (event.findAll(value).count() != 1) return FastPath.AI
        return FastPath.LOCAL
    }

    /** 空 AI 结果仅在确实有日程线索时升级，避免把普通聊天强行变成事件。 */
    fun hasScheduleClue(text: String): Boolean = datePattern.containsMatchIn(text) && event.containsMatchIn(text)
    fun hasExplicitTime(text: String): Boolean = timePattern.containsMatchIn(text)
}
