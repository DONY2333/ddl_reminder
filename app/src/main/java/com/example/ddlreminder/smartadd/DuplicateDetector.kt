package com.example.ddlreminder.smartadd

import com.example.ddlreminder.data.DdlEntity

object DuplicateDetector {
    fun mark(drafts: List<ScheduleDraft>, existing: List<DdlEntity>): List<ScheduleDraft> {
        val keys = existing.map { it.title.trim() to it.deadlineMillis }.toSet()
        return drafts.map { it.copy(possibleDuplicate = (it.title.trim() to it.deadlineMillis) in keys) }
    }
}
