package ru.nto.guestmanagement.mobile.ui.list

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.nto.guestmanagement.mobile.R
import ru.nto.guestmanagement.mobile.data.ApiResult
import ru.nto.guestmanagement.mobile.data.RequestStatus
import ru.nto.guestmanagement.mobile.data.RequestSummary
import ru.nto.guestmanagement.mobile.data.RequestsApi
import ru.nto.guestmanagement.mobile.data.formatDate
import ru.nto.guestmanagement.mobile.data.formatInterval
import ru.nto.guestmanagement.mobile.di.AppContainer
import ru.nto.guestmanagement.mobile.ui.TestIds
import ru.nto.guestmanagement.mobile.ui.common.StatusLabel
import ru.nto.guestmanagement.mobile.ui.common.containerViewModel
import ru.nto.guestmanagement.mobile.ui.refresh.RefreshGate
import ru.nto.guestmanagement.mobile.ui.refresh.ResumeEffect

class RequestListViewModel(private val container: AppContainer) : ViewModel() {
    private val api = RequestsApi(container.httpClient)
    private val gate = RefreshGate()
    private var generation = 0

    /** `null` — список ещё не загружен. */
    var items by mutableStateOf<List<RequestSummary>?>(null)
        private set

    /** `null` — фильтр «Все», параметр `status` не передаётся. */
    var filter by mutableStateOf<RequestStatus?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    /** Первоначальная загрузка не удалась: показываем сообщение и кнопку обновления. */
    var initialError by mutableStateOf(false)
        private set

    /** Повторная загрузка не удалась: данные остаются, показываем «Нет связи». */
    var offline by mutableStateOf(false)
        private set

    fun refresh(onSessionExpired: () -> Unit) {
        load(filter, onSessionExpired)
    }

    fun selectFilter(status: RequestStatus?, onSessionExpired: () -> Unit) {
        if (status == filter) return
        filter = status
        // Смена фильтра: старые данные к новому фильтру не относятся.
        items = null
        initialError = false
        offline = false
        gate.cancel()
        load(status, onSessionExpired)
    }

    private fun load(status: RequestStatus?, onSessionExpired: () -> Unit) {
        val job = gate.launch(viewModelScope) {
            val code = container.session.current()
            if (code == null) {
                expire(onSessionExpired)
                return@launch
            }
            when (val result = api.list(code, status)) {
                is ApiResult.Success -> {
                    items = result.value
                    initialError = false
                    offline = false
                }

                is ApiResult.Unauthorized -> expire(onSessionExpired)

                is ApiResult.Network, is ApiResult.Failure, is ApiResult.NotFound, is ApiResult.Conflict -> {
                    if (items == null) {
                        initialError = true
                        offline = false
                    } else {
                        offline = true
                        initialError = false
                    }
                }

                is ApiResult.FieldErrors -> {
                    if (items == null) {
                        initialError = true
                        offline = false
                    } else {
                        offline = true
                        initialError = false
                    }
                }
            }
        } ?: return

        val current = ++generation
        loading = true
        job.invokeOnCompletion {
            if (current == generation) loading = false
        }
    }

    private suspend fun expire(onSessionExpired: () -> Unit) {
        container.session.clear()
        onSessionExpired()
    }
}

/**
 * Список заявок: фильтры, порядок как у API, пустое состояние, ошибки и «Нет связи»,
 * ручное обновление, перечитывание при возврате на экран, выход.
 */
@Composable
fun RequestListScreen(
    container: AppContainer,
    onOpenDetails: (Long) -> Unit,
    onAddRequest: () -> Unit,
    onLogout: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    val vm = containerViewModel { RequestListViewModel(container) }
    val scope = rememberCoroutineScope()

    ResumeEffect { vm.refresh(onSessionExpired) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.list_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { vm.refresh(onSessionExpired) },
                modifier = Modifier.testTag(TestIds.REQUESTS_REFRESH),
            ) {
                Text(stringResource(R.string.action_refresh))
            }
            TextButton(
                onClick = {
                    scope.launch {
                        container.session.clear()
                        onLogout()
                    }
                },
                modifier = Modifier.testTag(TestIds.REQUESTS_LOGOUT),
            ) {
                Text(stringResource(R.string.action_logout))
            }
        }

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterItem(
                label = stringResource(R.string.filter_all),
                selected = vm.filter == null,
                tag = TestIds.REQUESTS_FILTER_ALL,
                onClick = { vm.selectFilter(null, onSessionExpired) },
            )
            FilterItem(
                label = stringResource(R.string.filter_pending),
                selected = vm.filter == RequestStatus.PENDING,
                tag = TestIds.REQUESTS_FILTER_PENDING,
                onClick = { vm.selectFilter(RequestStatus.PENDING, onSessionExpired) },
            )
            FilterItem(
                label = stringResource(R.string.filter_approved),
                selected = vm.filter == RequestStatus.APPROVED,
                tag = TestIds.REQUESTS_FILTER_APPROVED,
                onClick = { vm.selectFilter(RequestStatus.APPROVED, onSessionExpired) },
            )
            FilterItem(
                label = stringResource(R.string.filter_rejected),
                selected = vm.filter == RequestStatus.REJECTED,
                tag = TestIds.REQUESTS_FILTER_REJECTED,
                onClick = { vm.selectFilter(RequestStatus.REJECTED, onSessionExpired) },
            )
            FilterItem(
                label = stringResource(R.string.filter_cancelled),
                selected = vm.filter == RequestStatus.CANCELLED,
                tag = TestIds.REQUESTS_FILTER_CANCELLED,
                onClick = { vm.selectFilter(RequestStatus.CANCELLED, onSessionExpired) },
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val list = vm.items
            when {
                list == null && vm.initialError -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.list_error),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.testTag(TestIds.REQUESTS_ERROR),
                        )
                    }
                }

                list == null -> {
                    if (vm.loading) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                }

                list.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.list_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag(TestIds.REQUESTS_EMPTY),
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 8.dp,
                            bottom = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(list) { index, item ->
                            RequestCard(
                                index = index,
                                item = item,
                                onOpen = onOpenDetails,
                            )
                        }
                    }
                }
            }
        }

        if (vm.offline) {
            Text(
                text = stringResource(R.string.offline),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag(TestIds.REQUESTS_OFFLINE),
            )
        }

        Button(
            onClick = onAddRequest,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(TestIds.REQUESTS_ADD),
        ) {
            Text(stringResource(R.string.action_add))
        }
    }
}

@Composable
private fun FilterItem(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun RequestCard(
    index: Int,
    item: RequestSummary,
    onOpen: (Long) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestIds.requestsPos(index))
            // Карточка не объединяет семантику потомков: клик задаётся вручную.
            .pointerInput(item.id) { detectTapGestures { onOpen(item.id) } }
            .semantics { onClick { onOpen(item.id); true } },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fullName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag(TestIds.REQUEST_NAME),
                )
                Spacer(Modifier.height(2.dp))
                Row {
                    Text(
                        text = formatDate(item.validFrom),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(TestIds.REQUEST_DATE),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = formatInterval(item.validFrom, item.validTo),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(TestIds.REQUEST_TIME),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            StatusLabel(status = item.status, tag = TestIds.REQUEST_STATUS)
        }
    }
}
