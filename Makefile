# Build and verification targets for the standalone Go Mode contracts and shell.
.DEFAULT_GOAL := help
.PHONY: android-e2e android-e2e-ci android-lint android-push android-sdk android-start-emulator android-stop-emulator android-test benchmark build fix frontend-build git-hooks help release screenshots-update test test-smoke upgrade verify

# Keep local and CI release tooling on the same version.
GORELEASER ?= go run github.com/goreleaser/goreleaser/v2@v2.20.0

ANDROID_LINT_TASKS := \
	:car-dial-fixture:detekt \
	:car-dial-fixture:ktlintCheck \
	:even-g2-sdk:detekt \
	:even-g2-sdk:ktlintCheck \
	:even-g2-testing:detekt \
	:even-g2-testing:ktlintCheck \
	:gomode:detekt \
	:gomode:ktlintCheck \
	:gomode-testing:detekt \
	:gomode-testing:ktlintCheck \
	:halo-sdk:detekt \
	:halo-sdk:ktlintCheck \
	:halo-testing:detekt \
	:halo-testing:ktlintCheck

# Android lint needs compiled classes, so keep it with build-dependent checks.
ANDROID_TEST_TASKS := \
	:car-dial-fixture:assembleDebug \
	:car-dial-fixture:assembleDebugAndroidTest \
	:car-dial-fixture:lintDebug \
	:even-g2-sdk:assembleDebug \
	:even-g2-sdk:assembleDebugAndroidTest \
	:even-g2-sdk:createDebugUnitTestCoverageReport \
	:even-g2-sdk:lintDebug \
	:even-g2-sdk:testDebugUnitTest \
	:even-g2-testing:lintDebug \
	:gomode:assembleDebug \
	:gomode:assembleDebugAndroidTest \
	:gomode:createDebugUnitTestCoverageReport \
	:gomode:lintDebug \
	:gomode:testDebugUnitTest \
	:gomode-sdk:assemble \
	:gomode-testing:lintDebug \
	:halo-sdk:assembleDebug \
	:halo-sdk:assembleDebugAndroidTest \
	:halo-sdk:createDebugUnitTestCoverageReport \
	:halo-sdk:lintDebug \
	:halo-sdk:testDebugUnitTest \
	:halo-testing:lintDebug \
	:mcp-sdk:assemble \
	:oauth-sdk:assemble \
	:voicegateway-sdk:assemble

# ANDROID_VERBOSE=true replaces Gradle's --quiet with a plain log of every task
# and its outcome, so gate logs show which Android checks ran.
ANDROID_VERBOSE ?= false
ifeq ($(ANDROID_VERBOSE),true)
GRADLE_LOG_ARGS := --console=plain
E2E_LOG_ARGS := --verbose
else ifeq ($(ANDROID_VERBOSE),false)
GRADLE_LOG_ARGS := --quiet
E2E_LOG_ARGS :=
else
$(error ANDROID_VERBOSE must be true or false)
endif

# Keep Java processes scoped to each build, including Gradle launched by Python helpers.
# Compile Kotlin inside Gradle's single-use JVM so it cannot leave a compiler daemon.
export GRADLE_OPTS := $(GRADLE_OPTS) -Dorg.gradle.daemon=false -Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process

node_modules/.modules.yaml: package.json pnpm-lock.yaml
	@pnpm install --frozen-lockfile --silent
	@touch $@

help:
	@echo 'Go Mode - standalone contracts and shell'
	@echo ''
	@echo 'Available targets:'
	@printf '  %-27s - %s\n' 'make fix' 'Format code and regenerate SDKs, frontend assets, and file indexes'
	@printf '  %-27s - %s\n' 'make verify' 'Check Go, browser, Python, shell, and changed Android inputs'
	@printf '  %-27s - %s\n' 'make test' 'Run Go, browser, and changed Android builds and tests'
	@printf '  %-27s - %s\n' 'make test-smoke' 'Run local model voice smoke tests (slow, downloads models)'
	@printf '  %-27s - %s\n' 'make benchmark' 'Run Go benchmarks'
	@printf '  %-27s - %s\n' 'make build' 'Build Go packages and Android app and SDKs'
	@printf '  %-27s - %s\n' 'make android-lint' 'Run Android source static analysis and formatting checks'
	@printf '  %-27s - %s\n' 'make android-test' 'Run Android builds, platform lint, unit tests, and coverage'
	@printf '  %-27s - %s\n' 'make android-e2e' 'Run all model-free app, gateway, and Telecom emulator tests'
	@printf '  %-27s - %s\n' 'make android-push' 'Build and install Go Mode on connected devices'
	@printf '  %-27s - %s\n' 'make android-start-emulator' 'Provision, start, or reuse the Android emulator'
	@printf '  %-27s - %s\n' 'make android-stop-emulator' 'Stop the Android emulator'
	@printf '  %-27s - %s\n' 'make screenshots-update' 'Capture and publish repeatable native Android scenes'
	@printf '  %-27s - %s\n' 'make git-hooks' 'Install versioned Git hooks'
	@printf '  %-27s - %s\n' 'make upgrade' 'Upgrade Go and pnpm dependencies'
	@echo ''
	@echo 'Set ANDROID_VERBOSE=true to log each Gradle task and its outcome.'

fix: node_modules/.modules.yaml
	@go run ./internal/cmd/gen-sdk
	@pnpm exec node scripts/css_module_types.mjs --source-path web/src --write
	@pnpm exec node scripts/css_module_types.mjs --source-path internal/gatewayfrontend/src --write
	@go tool golangci-lint fmt
	@go tool golangci-lint run --show-stats=false --fix ./...
	@pnpm exec eslint e2e/voice-preview/*.js e2e/voice-preview/*.tsx internal/gatewayfrontend/src scripts/*.mjs web/src web/tests --fix
	@pnpm exec prettier --write --log-level warn .github/workflows e2e/voice-preview eslint.config.js internal/gatewayfrontend/index.html internal/gatewayfrontend/src internal/gatewayfrontend/tsconfig.json package.json scripts/*.mjs web/src web/tests
	@uv run --locked ruff check --quiet --fix scripts
	@uv run --locked ruff format scripts >/dev/null
	@files=$$(git ls-files '*.sh' 'scripts/hooks/*'); [ -z "$$files" ] || go tool shfmt -w $$files
	@./scripts/android-checks.sh \
		:car-dial-fixture:ktlintFormat \
		:even-g2-sdk:ktlintFormat \
		:even-g2-testing:ktlintFormat \
		:gomode:ktlintFormat \
		:gomode-testing:ktlintFormat \
		:halo-sdk:ktlintFormat \
		:halo-testing:ktlintFormat \
		$(GRADLE_LOG_ARGS)
	@pnpm exec node scripts/build_frontend.mjs --write
	@uv run --locked python scripts/update_agents_file_index.py

verify: node_modules/.modules.yaml
	@./scripts/run-concurrently.sh sdk,workflows,go,format,lint,ts,frontend,python,shell,misc,android \
		'go run ./internal/cmd/gen-sdk -check' \
		'go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12' \
		'go mod tidy -diff && go tool golangci-lint run ./...' \
		'pnpm exec prettier --check --log-level warn .github/workflows e2e/voice-preview eslint.config.js internal/gatewayfrontend/index.html internal/gatewayfrontend/src internal/gatewayfrontend/tsconfig.json package.json scripts/*.mjs web/src web/tests' \
		'pnpm exec eslint --max-warnings 0 e2e/voice-preview/*.js e2e/voice-preview/*.tsx internal/gatewayfrontend/src scripts/*.mjs web/src web/tests' \
		'pnpm exec node scripts/css_module_types.mjs --source-path web/src && pnpm exec node scripts/css_module_types.mjs --source-path internal/gatewayfrontend/src && pnpm exec tsc --noEmit -p internal/gatewayfrontend/tsconfig.json' \
		'pnpm exec node scripts/build_frontend.mjs' \
		'uv run --locked ruff check --quiet scripts && uv run --locked ruff format --check --quiet scripts && uv run --locked python scripts/android_paths.py >/dev/null && uv run --locked python scripts/generate_thinking_score.py --check' \
		'files=$$(git ls-files "*.sh" "scripts/hooks/*"); [ -z "$$files" ] || go tool shfmt -d $$files' \
		'uv run --locked python scripts/lint_binaries.py && uv run --locked python scripts/update_agents_file_index.py --check && git diff --check' \
		'./scripts/android-checks.sh $(ANDROID_LINT_TASKS) $(GRADLE_LOG_ARGS)'

git-hooks:
	@./scripts/install-git-hooks.sh

release:
	@$(GORELEASER) release --clean

test: node_modules/.modules.yaml
	@./scripts/run-concurrently.sh go,browser-scripts,browser \
		'go test ./...' \
		'pnpm exec node --test --test-reporter=./scripts/quiet-test-reporter.mjs scripts/browser_integration.test.mjs scripts/build_frontend.test.mjs scripts/css_module_types.test.mjs scripts/voice_preview.test.mjs' \
		'NODE_OPTIONS=--no-experimental-webstorage pnpm exec tsx --test --conditions=browser --test-force-exit --test-reporter=./scripts/quiet-test-reporter.mjs --import ./web/tests/setup-dom.ts "internal/gatewayfrontend/src/**/*.test.tsx" "web/src/**/*.test.ts" "web/src/**/*.test.tsx"'
	@./scripts/android-checks.sh $(ANDROID_TEST_TASKS) $(GRADLE_LOG_ARGS)

frontend-build: node_modules/.modules.yaml
	@pnpm exec node scripts/css_module_types.mjs --source-path web/src --write
	@pnpm exec node scripts/css_module_types.mjs --source-path internal/gatewayfrontend/src --write
	@pnpm exec node scripts/build_frontend.mjs --write

build: android-sdk node_modules/.modules.yaml frontend-build
	@pnpm exec node scripts/css_module_types.mjs --source-path web/src
	@go build ./...
	@cd ./android && ./gradlew :even-g2-sdk:assembleDebug :gomode:assembleDebug :halo-sdk:assembleDebug :gomode-sdk:assemble :mcp-sdk:assemble :oauth-sdk:assemble :voicegateway-sdk:assemble $(GRADLE_LOG_ARGS)

# Benchmarks exercise the Go request paths; the browser package has no benchmark suite.
benchmark:
	@go test ./... -run '^$$' -bench . -benchmem

# Slow: starts local ASR, LLM, and TTS models and sends spoken audio through
# the voice gateway over WebRTC. The smoke build tag keeps it out of make test.
test-smoke:
	@go test -tags=smoke -run '^TestSmoke' -v -timeout 30m ./voicegateway/voicertc/

upgrade:
	@go get -u ./...
	@go mod tidy
	@pnpm update --latest
	@cd ./android && ./gradlew --no-daemon dependencyUpdates -Drevision=release

android-lint: android-sdk
	@cd ./android && ./gradlew $(ANDROID_LINT_TASKS) $(GRADLE_LOG_ARGS)

android-test: android-sdk
	@cd ./android && ./gradlew $(ANDROID_TEST_TASKS) $(GRADLE_LOG_ARGS)

# Installs the Android SDK packages required by the app build.
android-sdk:
	@uv run --locked python scripts/android_sdk.py check

# Slow: provisions the system image and test AVD when missing, then starts or
# reuses the headless Go Mode test emulator.
android-start-emulator:
	@uv run --locked python scripts/android_start_emulator.py --auto-reuse

android-stop-emulator:
	@uv run --locked python scripts/android_stop_emulator.py

# Builds, installs, and launches the app on a connected device.
android-push: android-sdk
	@cd ./android && ./gradlew :gomode:assembleDebug $(GRADLE_LOG_ARGS)
	@uv run --locked python scripts/android_push.py

# Slow: runs all model-free app and independent Telecom tests on an emulator.
android-e2e:
	@uv run --locked python scripts/android_start_emulator.py --auto-reuse
	@uv run --locked python scripts/android_e2e.py $(E2E_LOG_ARGS)

# Slow: runs the CI subset of model-free emulator tests, one case per mechanism.
android-e2e-ci:
	@uv run --locked python scripts/android_start_emulator.py --auto-reuse
	@uv run --locked python scripts/android_e2e.py --ci $(E2E_LOG_ARGS)

# Slow: runs the real app on the emulator twice, requires matching passes, and
# publishes lossless WebP files.
screenshots-update: android-sdk
	@uv run --locked python scripts/android_start_emulator.py --auto-reuse
	@uv run --locked python scripts/android_screenshots.py
