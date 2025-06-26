package com.lvsmsmch.aichat.utils

import org.slf4j.LoggerFactory

inline fun <reified T : Any> logDatabaseEvent(event: DatabaseEvent<T>, collectionName: String) {
    val logger = LoggerFactory.getLogger("DatabaseLogger")
    val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(java.util.Date())

    // Extract ID
    val extractedId = try {
        val field = event.latestObject.javaClass.declaredFields.find { it.name == "id" }
        if (field != null) {
            field.isAccessible = true
            field.get(event.latestObject).toString()
        } else {
            "unknown"
        }
    } catch (e: Exception) {
        "unknown"
    }

    // Get entity type name
    val entityType = T::class.simpleName ?: "UnknownType"

    // Operation type with visual indicator
    val operationType = when (event) {
        is DatabaseEvent.Created -> "[+] INSERT"
        is DatabaseEvent.Updated -> "[*] UPDATE"
        is DatabaseEvent.Deleted -> "[-] DELETE"
    }

    // Build log message without helper functions
    val logMsg = buildString {
        appendLine("+------------------------------------------------+")
        appendLine("|                 DATABASE EVENT                 |")
        appendLine("+------------------------------------------------+")
        appendLine("| Operation: $operationType")
        appendLine("| Collection: $collectionName")
        appendLine("| Type: $entityType")
//        appendLine("| ID: $extractedId")
//        appendLine("| Time: $timestamp")
        appendLine("+------------------------------------------------+")

        when (event) {
            is DatabaseEvent.Created -> {
                appendLine("| NEW DATA:")
                // Remove class name from toString output
                val newData = event.new.toString()
                    .replaceFirst("${event.new.javaClass.simpleName}(", "")
                    .removeSuffix(")")
                appendLine("| $newData")
            }
            is DatabaseEvent.Updated -> {
                appendLine("| OLD DATA:")
                val oldData = event.old.toString()
                    .replaceFirst("${event.old.javaClass.simpleName}(", "")
                    .removeSuffix(")")
                appendLine("| $oldData")
                appendLine("|")
                appendLine("| NEW DATA:")
                val newData = event.new.toString()
                    .replaceFirst("${event.new.javaClass.simpleName}(", "")
                    .removeSuffix(")")
                appendLine("| $newData")

                // Show changed fields manually
                appendLine("|")
                appendLine("| CHANGED FIELDS:")
                var hasChanges = false
                // Manually inspect the fields
                try {
                    for (field in event.old.javaClass.declaredFields) {
                        field.isAccessible = true
                        val oldValue = field.get(event.old)
                        val newValue = field.get(event.new)

                        if (oldValue != newValue) {
                            hasChanges = true
                            appendLine("| • ${field.name}: $oldValue → $newValue")
                        }
                    }
                    if (!hasChanges) {
                        appendLine("| • No changes detected")
                    }
                } catch (e: Exception) {
                    appendLine("| • Error comparing fields: ${e.message}")
                }
            }
            is DatabaseEvent.Deleted -> {
                appendLine("| DELETED DATA:")
                val oldData = event.old.toString()
                    .replaceFirst("${event.old.javaClass.simpleName}(", "")
                    .removeSuffix(")")
                appendLine("| $oldData")
            }
        }
        appendLine("+------------------------------------------------+")
    }

    // Log the formatted message
    logger.info(logMsg)
}