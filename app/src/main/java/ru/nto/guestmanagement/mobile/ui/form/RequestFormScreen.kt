package ru.nto.guestmanagement.mobile.ui.form

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.coroutines.launch
import ru.nto.guestmanagement.mobile.R
import ru.nto.guestmanagement.mobile.data.ApiResult
import ru.nto.guestmanagement.mobile.data.CreateRequestBody
import ru.nto.guestmanagement.mobile.data.RequestsApi
import ru.nto.guestmanagement.mobile.data.toApi
import ru.nto.guestmanagement.mobile.di.AppContainer
import ru.nto.guestmanagement.mobile.ui.TestIds
import ru.nto.guestmanagement.mobile.ui.common.containerViewModel

private val dateInputFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)

private val timeInputFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)

private val phonePattern = Regex("\\+7[0-9]{10}")

private fun parseDateInput(value: String): LocalDate? = try {
    LocalDate.parse(value, dateInputFormat)
} catch (e: Exception) {
    null
}

private fun parseTimeInput(value: String): LocalTime? = try {
    LocalTime.parse(value, timeInputFormat)
} catch (e: Exception) {
    null
}

/** Клиентские ошибки полей: ресурсы строк; `null` — поле корректно. */
data class ClientErrors(
    val fullName: Int? = null,
    val phone: Int? = null,
    val organization: Int? = null,
    val purpose: Int? = null,
    val date: Int? = null,
    val validFrom: Int? = null,
    val validTo: Int? = null,
) {
    val hasAny: Boolean
        get() = fullName != null || phone != null || organization != null || purpose != null ||
            date != null || validFrom != null || validTo != null
}

class RequestFormViewModel(private val container: AppContainer) : ViewModel() {
    private val api = RequestsApi(container.httpClient)

    var fullName by mutableStateOf("")
        private set
    var phone by mutableStateOf("")
        private set
    var organization by mutableStateOf("")
        private set
    var purpose by mutableStateOf("")
        private set
    var date by mutableStateOf("")
        private set
    var timeFrom by mutableStateOf("")
        private set
    var timeTo by mutableStateOf("")
        private set

    var attempted by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var clientErrors by mutableStateOf(ClientErrors())
        private set
    var serverErrors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var commonErrorRes by mutableStateOf<Int?>(null)
        private set

    fun onFullNameChange(value: String) {
        fullName = value
        clearServerError("fullName")
        revalidate()
    }

    fun onPhoneChange(value: String) {
        phone = value
        clearServerError("phone")
        revalidate()
    }

    fun onOrganizationChange(value: String) {
        organization = value
        clearServerError("organization")
        revalidate()
    }

    fun onPurposeChange(value: String) {
        purpose = value
        clearServerError("purpose")
        revalidate()
    }

    fun onDateChange(value: String) {
        date = value
        // Правка даты не сбрасывает серверные ошибки validFrom/validTo.
        revalidate()
    }

    fun onTimeFromChange(value: String) {
        timeFrom = value
        clearServerError("validFrom")
        revalidate()
    }

    fun onTimeToChange(value: String) {
        timeTo = value
        clearServerError("validTo")
        revalidate()
    }

    private fun clearServerError(key: String) {
        if (serverErrors.containsKey(key)) serverErrors = serverErrors - key
    }

    private fun revalidate() {
        if (attempted) clientErrors = validate()
    }

    fun validate(): ClientErrors {
        val now = container.timeProvider.now()
        val name = fullName.trim()
        val phoneValue = phone.trim()
        val organizationValue = organization.trim()
        val purposeValue = purpose.trim()

        val parsedDate = parseDateInput(date)
        val parsedFrom = parseTimeInput(timeFrom)
        val parsedTo = parseTimeInput(timeTo)

        var dateError: Int? = null
        var fromError: Int? = null
        var toError: Int? = null

        if (parsedDate == null) {
            dateError = R.string.form_error_date
            if (parsedFrom == null) fromError = R.string.form_error_time_from_format
            if (parsedTo == null) toError = R.string.form_error_time_to_format
        } else {
            if (parsedFrom == null) {
                fromError = R.string.form_error_time_from_format
            } else if (parsedDate.atTime(parsedFrom).isBefore(now)) {
                fromError = R.string.form_error_time_from_past
            }

            if (parsedTo == null) {
                toError = R.string.form_error_time_to_format
            } else if (parsedFrom != null && !parsedDate.atTime(parsedTo).isAfter(parsedDate.atTime(parsedFrom))) {
                toError = R.string.form_error_time_to_order
            }
        }

        return ClientErrors(
            fullName = if (name.length !in 5..150) R.string.form_error_full_name else null,
            phone = if (!phonePattern.matches(phoneValue)) R.string.form_error_phone else null,
            organization = if (organizationValue.length > 150) R.string.form_error_organization else null,
            purpose = if (purposeValue.length !in 5..200) R.string.form_error_purpose else null,
            date = dateError,
            validFrom = fromError,
            validTo = toError,
        )
    }

    fun submit(onCreated: () -> Unit, onSessionExpired: () -> Unit) {
        if (loading) return
        attempted = true
        commonErrorRes = null
        serverErrors = emptyMap()
        val errors = validate()
        clientErrors = errors
        if (errors.hasAny) return

        val parsedDate = parseDateInput(date) ?: return
        val parsedFrom = parseTimeInput(timeFrom) ?: return
        val parsedTo = parseTimeInput(timeTo) ?: return
        val body = CreateRequestBody(
            fullName = fullName.trim(),
            phone = phone.trim(),
            organization = organization.trim().ifEmpty { null },
            purpose = purpose.trim(),
            validFrom = parsedDate.atTime(parsedFrom).toApi(),
            validTo = parsedDate.atTime(parsedTo).toApi(),
        )

        loading = true
        viewModelScope.launch {
            try {
                val code = container.session.current()
                if (code == null) {
                    container.session.clear()
                    onSessionExpired()
                    return@launch
                }
                when (val result = api.create(code, body)) {
                    is ApiResult.Success -> onCreated()
                    is ApiResult.Unauthorized -> {
                        container.session.clear()
                        onSessionExpired()
                    }

                    is ApiResult.FieldErrors -> {
                        serverErrors = result.errors
                        if (result.errors.isEmpty()) commonErrorRes = R.string.form_error_other
                    }

                    is ApiResult.Conflict -> commonErrorRes = R.string.form_error_duplicate
                    is ApiResult.Network -> commonErrorRes = R.string.form_error_network
                    else -> commonErrorRes = R.string.form_error_other
                }
            } finally {
                loading = false
            }
        }
    }
}

/**
 * Форма новой заявки: поля, клиентская валидация (время «сейчас» — `container.timeProvider`),
 * ошибки сервера, защита от повторной отправки. После успеха вызывается [onCreated].
 */
@Composable
fun RequestFormScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onCreated: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    val vm = containerViewModel { RequestFormViewModel(container) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.testTag(TestIds.FORM_BACK),
            ) {
                Text(stringResource(R.string.action_back))
            }
            Text(
                text = stringResource(R.string.form_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            FormTextField(
                value = vm.fullName,
                onValueChange = vm::onFullNameChange,
                labelRes = R.string.form_full_name,
                tag = TestIds.FORM_FULL_NAME,
            )
            FieldError(vm.serverErrors, "fullName", TestIds.FORM_ERROR_FULL_NAME, vm.clientErrors.fullName)

            FormTextField(
                value = vm.phone,
                onValueChange = vm::onPhoneChange,
                labelRes = R.string.form_phone,
                tag = TestIds.FORM_PHONE,
                keyboardType = KeyboardType.Phone,
            )
            FieldError(vm.serverErrors, "phone", TestIds.FORM_ERROR_PHONE, vm.clientErrors.phone)

            FormTextField(
                value = vm.organization,
                onValueChange = vm::onOrganizationChange,
                labelRes = R.string.form_organization,
                tag = TestIds.FORM_ORGANIZATION,
            )
            FieldError(vm.serverErrors, "organization", TestIds.FORM_ERROR_ORGANIZATION, vm.clientErrors.organization)

            FormTextField(
                value = vm.purpose,
                onValueChange = vm::onPurposeChange,
                labelRes = R.string.form_purpose,
                tag = TestIds.FORM_PURPOSE,
            )
            FieldError(vm.serverErrors, "purpose", TestIds.FORM_ERROR_PURPOSE, vm.clientErrors.purpose)

            FormTextField(
                value = vm.date,
                onValueChange = vm::onDateChange,
                labelRes = R.string.form_date,
                tag = TestIds.FORM_DATE,
            )
            FieldError(vm.serverErrors, "date", TestIds.FORM_ERROR_DATE, vm.clientErrors.date)

            FormTextField(
                value = vm.timeFrom,
                onValueChange = vm::onTimeFromChange,
                labelRes = R.string.form_time_from,
                tag = TestIds.FORM_TIME_FROM,
            )
            FieldError(vm.serverErrors, "validFrom", TestIds.FORM_ERROR_VALID_FROM, vm.clientErrors.validFrom)

            FormTextField(
                value = vm.timeTo,
                onValueChange = vm::onTimeToChange,
                labelRes = R.string.form_time_to,
                tag = TestIds.FORM_TIME_TO,
            )
            FieldError(vm.serverErrors, "validTo", TestIds.FORM_ERROR_VALID_TO, vm.clientErrors.validTo)

            Spacer(Modifier.height(8.dp))

            vm.commonErrorRes?.let { message ->
                Text(
                    text = stringResource(message),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .testTag(TestIds.FORM_ERROR_COMMON),
                )
            }

            if (vm.loading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 8.dp)
                        .testTag(TestIds.FORM_LOADING),
                    strokeWidth = 2.dp,
                )
            }

            Button(
                onClick = { vm.submit(onCreated, onSessionExpired) },
                enabled = !vm.loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestIds.FORM_SUBMIT),
            ) {
                Text(stringResource(R.string.form_submit))
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    labelRes: Int,
    tag: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    )
}

@Composable
private fun FieldError(
    serverErrors: Map<String, String>,
    key: String,
    tag: String,
    clientErrorRes: Int?,
) {
    val text = serverErrors[key] ?: clientErrorRes?.let { stringResource(it) }
    if (text != null) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, bottom = 4.dp)
                .testTag(tag),
        )
    }
}
