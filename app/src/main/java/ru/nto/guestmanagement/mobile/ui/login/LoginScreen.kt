package ru.nto.guestmanagement.mobile.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.nto.guestmanagement.mobile.R
import ru.nto.guestmanagement.mobile.data.ApiResult
import ru.nto.guestmanagement.mobile.data.RequestsApi
import ru.nto.guestmanagement.mobile.di.AppContainer
import ru.nto.guestmanagement.mobile.ui.TestIds
import ru.nto.guestmanagement.mobile.ui.common.containerViewModel

/** Ровно четыре латинские буквы или цифры, регистр значим, пробелы не удаляются. */
private val employeeCode = Regex("[A-Za-z0-9]{4}")

class LoginViewModel(private val container: AppContainer) : ViewModel() {
    private val api = RequestsApi(container.httpClient)

    var code by mutableStateOf("")
        private set

    var loading by mutableStateOf(false)
        private set

    /** Ресурс текста ошибки; `null` — ошибки нет. */
    var errorRes by mutableStateOf<Int?>(null)
        private set

    /** Сохранённый ранее код найден — вход пропускается без запроса `/auth`. */
    var loggedIn by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            if (container.session.current() != null) loggedIn = true
        }
    }

    val canSubmit: Boolean
        get() = !loading && employeeCode.matches(code)

    fun onCodeChange(value: String) {
        code = value
        errorRes = null
    }

    fun submit() {
        if (!canSubmit) return
        errorRes = null
        loading = true
        viewModelScope.launch {
            try {
                when (api.auth(code)) {
                    is ApiResult.Success -> {
                        container.session.save(code)
                        loggedIn = true
                    }

                    is ApiResult.Unauthorized -> errorRes = R.string.login_error_unknown_code
                    is ApiResult.Network -> errorRes = R.string.login_error_network
                    else -> errorRes = R.string.login_error_other
                }
            } finally {
                loading = false
            }
        }
    }
}

/**
 * Экран входа: поле кода, кнопка «Войти», запрос `/api/{code}/auth`,
 * сохранение кода в `container.session`, ошибки, сообщение «Сессия завершена» при [sessionExpired].
 * Если код уже сохранён с прошлого запуска — сразу вызывается [onLoggedIn].
 */
@Composable
fun LoginScreen(container: AppContainer, sessionExpired: Boolean, onLoggedIn: () -> Unit) {
    val vm = containerViewModel { LoginViewModel(container) }

    LaunchedEffect(vm.loggedIn) {
        if (vm.loggedIn) onLoggedIn()
    }
    if (vm.loggedIn) return

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.login_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = vm.code,
                onValueChange = vm::onCodeChange,
                label = { Text(stringResource(R.string.login_code_label)) },
                singleLine = true,
                enabled = !vm.loading,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestIds.AUTH_CODE),
            )

            if (sessionExpired) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.login_session_expired),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TestIds.AUTH_SESSION_EXPIRED),
                )
            }

            vm.errorRes?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TestIds.AUTH_ERROR),
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = vm::submit,
                enabled = vm.canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestIds.AUTH_SUBMIT),
            ) {
                Text(stringResource(R.string.login_submit))
            }

            if (vm.loading) {
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(24.dp)
                        .testTag(TestIds.AUTH_LOADING),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}
