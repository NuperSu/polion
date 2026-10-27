package io.polion.core.model

@JvmInline
value class PortfolioId(
    val value: String,
) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class OwnerId(
    val value: String,
) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class TrackingTargetId(
    val value: String,
) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class AccountId(
    val value: String,
) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class NetworkId(
    val value: String,
) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class AssetId(
    val value: String,
) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class SourceId(
    val value: String,
) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class ProviderId(
    val value: String,
) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class SourceRecordId(
    val value: String,
) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class ActivityId(
    val value: String,
) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class EventId(
    val value: String,
) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class SyncJobId(
    val value: String,
) {
    init {
        require(value.isNotBlank())
    }
}
