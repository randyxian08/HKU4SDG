package org.hku4sdg.handrail

/** Simplified characters used in this app, shown as Traditional when the reader chose 繁體. */
internal object Han {
    private val map: Map<Char, Char> = mapOf(
        '调' to '調', '这' to '這', '说' to '說', '语' to '語', '请' to '請', '见' to '見',
        '现' to '現', '时' to '時', '过' to '過', '还' to '還', '对' to '對', '开' to '開',
        '关' to '關', '书' to '書', '馆' to '館', '长' to '長', '务' to '務', '页' to '頁',
        '网' to '網', '显' to '顯', '认' to '認', '确' to '確', '择' to '擇', '选' to '選',
        '后' to '後', '从' to '從', '会' to '會', '来' to '來', '点' to '點', '经' to '經',
        '发' to '發', '为' to '為', '么' to '麼', '吗' to '嗎', '听' to '聽', '暂' to '暫',
        '仅' to '僅', '设' to '設', '帮' to '幫', '轻' to '輕', '试' to '試', '圆' to '圓',
        '导' to '導', '结' to '結', '记' to '記', '录' to '錄', '删' to '刪', '进' to '進',
        '让' to '讓', '个' to '個', '门' to '門', '问' to '問', '题' to '題', '应' to '應',
        '该' to '該', '软' to '軟', '里' to '裡', '于' to '於', '与' to '與', '无' to '無',
        '电' to '電', '话' to '話', '机' to '機', '览' to '覽', '标' to '標', '签' to '簽',
        '图' to '圖', '换' to '換', '继' to '繼', '续' to '續', '许' to '許', '别' to '別',
        '输' to '輸', '统' to '統', '议' to '議', '协' to '協', '简' to '簡', '体' to '體',
        '粤' to '粵', '条' to '條', '当' to '當', '没' to '沒', '复' to '復', '国' to '國',
        '东' to '東', '车' to '車'
    )

    fun convert(value: String): String = buildString(value.length) {
        value.forEach { append(map[it] ?: it) }
    }
}
