package dev.waterctl.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一次用水记录。
 *
 * 原 Web 版没有这个功能（用完即忘），这是本次原生实现新增的本地记录：
 * 只保存开始时间、时长、设备名 —— 全部留在手机上，不联网、不上传。
 */
@Entity(tableName = "water_records")
data class WaterRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 会话开始时刻（epoch millis） */
    val startedAt: Long,
    /** 会话时长（秒） */
    val durationSeconds: Int,
    /** 水控器蓝牙名 */
    val deviceName: String,
)
