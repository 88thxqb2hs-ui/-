package ru.nto.guestmanagement.mobile.ui.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.nto.guestmanagement.mobile.R
import ru.nto.guestmanagement.mobile.data.ApiResult
import ru.nto.guestmanagement.mobile.data.RequestDetails
import ru.nto.guestmanagement.mobile.data.RequestStatus
import ru.nto.guestmanagement.mobile.data.RequestsApi
import ru.nto.guestmanagement.mobile.data.formatDateTime
import ru.nto.guestmanagement.mobile.di.AppContainer
import ru.nto.guestmanagement.mobile.ui.TestIds
import ru.nto.guestmanagement.mobile.ui.common.StatusLabel
import ru.nto.guestmanagement.mobile.ui.common.containerViewModel
import ru.nto.guestmanagement.mobile.ui.refresh.RefreshGate
import ru.nto.guestmanagement.mobile.ui.refresh.ResumeEffect

class RequestDetailsViewModel(
    private val container: AppContainer,
    private val requestId: Long,
) : ViewModel() {
    private val api = RequestsApi(container.httpClient)
    private val gate = RefreshGate()
    private var generation = 0

    var details by mutableStateOf<RequestDetails?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var initialError by mutableStateOf(false)
        private set
    var offline by mutableStateOf(false)
        private set
    var cancelErrorRes by mutableStateOf<Int?>(null)
        private set
    var canceling by mutableStateOf(false)
        private set
    var showCancelConfirm by mutableStateOf(false)
        private set

    fun refresh(onSessionExpired: () -> Unit) {
        load(onSessionExpired)
    }

    private fun load(onSessionExpired: () -> Unit) {
        val job = gate.launch(viewModelScope) { fetch(onSessionExpired) } ?: return
        val current = ++generation
        loading = true
        job.invokeOnCompletion {
            if (current == generation) loading = false
        }
    }

    private suspend fun fetch(onSessionExpired: () -> Unit) {
        val code = container.session.current()
        if (code == null) {
            expire(onSessionExpired)
            return
        }
        when (val result = api.get(code, requestId)) {
            is ApiResult.Success -> {
                details = result.value
                initialError = false
                offline = false
            }

            is ApiResult.Unauthorized -> expire(onSessionExpired)

            else -> {
                if (details == null) {
                    initialError = true
                    offline = false
                } else {
                    offline = true
                    initialError = false
                }
            }
        }
    }

    fun askCancel() {
        showCancelConfirm = true
    }

    fun dismissCancel() {
        showCancelConfirm = false
    }

    fun confirmCancel(onSessionExpired: () -> Unit) {
        showCancelConfirm = false
        if (canceling) return
        canceling = true
        cancelErrorRes = null
        viewModelScope.launch {
            try {
                val code = container.session.current()
                if (code == null) {
                    expire(onSessionExpired)
                    return@launch
                }
                when (val result = api.cancel(code, requestId)) {
                    is ApiResult.Success -> {
                        details = result.value
                        cancelErrorRes = null
                        initialError = false
                        offline = false
                    }

                    is ApiResult.Unauthorized -> expire(onSessionExpired)

                    is ApiResult.Conflict -> {
                        cancelErrorRes = R.string.details_cancel_conflict
                        // Заявку уже рассмотрели — перечитываем актуальное состояние.
                        gate.cancel()
                        load(onSessionExpired)
                    }

                    is ApiResult.Network -> cancelErrorRes = R.string.details_cancel_network
                    else -> cancelErrorRes = R.string.details_cancel_other
                }
            } finally {
                canceling = false
            }
        }
    }

    private suspend fun expire(onSessionExpired: () -> Unit) {
        container.session.clear()
        onSessionExpired()
    }
}

/**
 * Детали заявки [requestId]: все поля, комментарий охраны, отмена с подтверждением,
 * обработка `409`, обновление и повтор при ошибке, возврат.
 */
@Composable
fun RequestDetailsScreen(
    container: AppContainer,
    requestId: Long,
    onBack: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    val vm = containerViewModel { RequestDetailsViewModel(container, requestId) }

    ResumeEffect { vm.refresh(onSessionExpired) }

    val details = vm.details

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.testTag(TestIds.DETAILS_BACK),
            ) {
                Text(stringResource(R.string.action_back))
            }
            Text(
                text = stringResource(R.string.details_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { vm.refresh(onSessionExpired) },
                modifier = Modifier.testTag(TestIds.DETAILS_REFRESH),
            ) {
                Text(stringResource(R.string.action_refresh))
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            when {
                details == null && vm.loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 32.dp),
                    )
                }

                details == null && vm.initialError -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.details_error),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.testTag(TestIds.DETAILS_ERROR),
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.refresh(onSessionExpired) },
                            modifier = Modifier.testTag(TestIds.DETAILS_RETRY),
                        ) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }

                details != null -> {
                    DetailField(
                        labelRes = R.string.details_label_id,
                        value = details.id.toString(),
                        tag = TestIds.DETAILS_ID,
                    )
                    DetailField(
                        labelRes = R.string.details_label_name,
                        value = details.fullName,
                        tag = TestIds.DETAILS_NAME,
                    )
                    DetailField(
                        labelRes = R.string.details_label_phone,
                        value = details.phone,
                        tag = TestIds.DETAILS_PHONE,
                    )
                    details.organization?.takeIf { it.isNotBlank() }?.let { organization ->
                        DetailField(
                            labelRes = R.string.details_label_organization,
                            value = organization,
                            tag = TestIds.DETAILS_ORGANIZATION,
                        )
                    }
                    DetailField(
                        labelRes = R.string.details_label_purpose,
                        value = details.purpose,
                        tag = TestIds.DETAILS_PURPOSE,
                    )
                    DetailField(
                        labelRes = R.string.details_label_from,
                        value = formatDateTime(details.validFrom),
                        tag = TestIds.DETAILS_VALID_FROM,
                    )
                    DetailField(
                        labelRes = R.string.details_label_to,
                        value = formatDateTime(details.validTo),
                        tag = TestIds.DETAILS_VALID_TO,
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.details_label_status),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        StatusLabel(status = details.status, tag = TestIds.DETAILS_STATUS)
                    }

                    DetailField(
                        labelRes = R.string.details_label_created,
                        value = formatDateTime(details.createdAt),
                        tag = TestIds.DETAILS_CREATED_AT,
                    )
                    DetailField(
                        labelRes = R.string.details_label_updated,
                        value = formatDateTime(details.updatedAt),
                        tag = TestIds.DETAILS_UPDATED_AT,
                    )

                    if (details.status == RequestStatus.REJECTED) {
                        DetailField(
                            labelRes = R.string.details_label_comment,
                            value = details.comment.orEmpty(),
                            tag = TestIds.DETAILS_COMMENT,
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        vm.cancelErrorRes?.let { message ->
            Text(
                text = stringResource(message),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag(TestIds.DETAILS_CANCEL_ERROR),
            )
        }

        if (vm.offline) {
            Text(
                text = stringResource(R.string.offline),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag(TestIds.DETAILS_OFFLINE),
            )
        }

        if (details?.status == RequestStatus.PENDING) {
            Button(
                onClick = vm::askCancel,
                enabled = !vm.canceling,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(TestIds.DETAILS_CANCEL),
            ) {
                Text(stringResource(R.string.details_cancel))
            }
        }
    }

    if (vm.showCancelConfirm) {
        AlertDialog(
            onDismissRequest = vm::dismissCancel,
            title = { Text(stringResource(R.string.details_cancel_title)) },
            text = { Text(stringResource(R.string.details_cancel_text)) },
            confirmButton = {
                TextButton(
                    onClick = { vm.confirmCancel(onSessionExpired) },
                    modifier = Modifier.testTag(TestIds.DETAILS_CANCEL_CONFIRM),
                ) {
                    Text(stringResource(R.string.details_cancel))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = vm::dismissCancel,
                    modifier = Modifier.testTag(TestIds.DETAILS_CANCEL_DISMISS),
                ) {
                    Text(stringResource(R.string.action_back))
                }
            },
        )
    }
}

@Composable
private fun DetailField(labelRes: Int, value: String, tag: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(tag),
        )
    }
}
