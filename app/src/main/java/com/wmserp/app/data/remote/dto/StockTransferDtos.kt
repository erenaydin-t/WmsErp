package com.wmserp.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TransferChoiceFieldDto(
    val fieldname: String,
    val label: String? = null,
    val options: String? = null,
)

/** Response of `stock_transfer.get_transfer_form`. */
@Serializable
data class TransferFormDto(
    val department: TransferChoiceFieldDto? = null,
    @SerialName("received_by") val receivedBy: TransferChoiceFieldDto? = null,
    @SerialName("has_batch_no") val hasBatchNo: Boolean = false,
)

@Serializable
data class TransferLineDto(
    @SerialName("batch_no") val batchNo: String? = null,
    val qty: Double = 0.0,
)

/** Response of `stock_transfer.create_transfer` when the draft was created. */
@Serializable
data class CreatedTransferDto(
    val name: String,
    val docstatus: Int = 0,
    val items: List<TransferLineDto> = emptyList(),
)
