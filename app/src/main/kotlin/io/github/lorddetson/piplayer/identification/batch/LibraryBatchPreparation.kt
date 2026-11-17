package io.github.lorddetson.piplayer.identification.batch

import android.content.Context
import io.github.lorddetson.piplayer.identification.IdentificationServices
import io.github.lorddetson.piplayer.identification.contract.ApplyRequest
import io.github.lorddetson.piplayer.library.Library
import io.github.lorddetson.piplayer.library.mirror.LibraryDecisions
import io.github.lorddetson.piplayer.settings.IdentificationSettings
import io.github.lorddetson.piplayer.settings.ProtectedField
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * The app's preparation of a group's high band (TI §8): each result the run takes on its own is stored as an Apply
 * decision with the batch's UUID, through the same decisions a review Apply makes, so that lists and Now Playing show
 * the result at once and the card of the group finds it decided.
 */
class LibraryBatchPreparation(
    private val context: Context,
    private val settings: IdentificationSettings = IdentificationSettings(context),
    private val clock: () -> Instant = { IdentificationServices.of(context).clock.instant() },
    private val decisions: () -> LibraryDecisions = { Library.decisions(context.applicationContext) },
) : BatchPreparation {

    override suspend fun fields(): Set<ProtectedField> = settings.protectedFields.first()

    override fun now(): Instant = clock()

    override suspend fun apply(request: ApplyRequest, batchUuid: String): Long? =
        decisions().apply(request, batchUuid = batchUuid)?.decisionId
}
