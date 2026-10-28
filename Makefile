# sdk-android developer tasks. Mirrors the proto vendoring of sdk-web / sdk-flutter / sdk-node /
# sdk-go / sdk-rust: proto/ is committed, so builds and CI never touch the Buf Schema Registry.

.PHONY: sync-protos proto-latest check-proto-pin protos descriptors typed-track check-codegen verify-version fmt lint test build abi-dump locks check-locks sample publish-bundle publish-central check ci

# `ci` must run `protos` before `check-codegen` diffs the generated files. Keep everything serial
# even under `make -jN`.
.NOTPARALLEL:

# BSR module + pinned commit that proto/ is vendored from (the same commit as sdk-flutter, sdk-node,
# sdk-go and sdk-rust). To bump: `make proto-latest`, set PROTO_COMMIT, then
# `make sync-protos protos` and review the diff.
PROTO_MODULE := buf.build/pugsh/pug
PROTO_COMMIT := 8b5d0680b10c4693b4d9d15d3dbfc663
# An allowlist: buf adds the transitive imports (buf/validate). sdk/devices is left out on purpose,
# since this SDK does no device registration.
PROTO_PATHS := --path sdk/events/v1 --path sdk/profiles/v1 --path common/events/v1 --path common/v1/property_value.proto

DESCRIPTORS := pug/src/test/resources/pug.binpb
# A JSON image keeps the source comments and names the custom options, which tools/gen-track reads.
EVENTS_IMAGE := build/typed-track/events.json
TYPED_TRACK := $(addprefix pug/src/main/kotlin/sh/pug/android/,TrackNamespace.kt Events.kt events/Events.kt internal/event/WellKnownEvents.kt)
GENERATED := $(DESCRIPTORS) $(TYPED_TRACK)
LOCKFILE := pug/gradle.lockfile

GRADLE := ./gradlew --console=plain
# The buf CLI pinned in gradle/libs.versions.toml, fetched from Maven Central by Gradle.
BUF := build/buf/buf

$(BUF): gradle/libs.versions.toml
	$(GRADLE) -q buf
	@touch $@

sync-protos: $(BUF)
	rm -rf proto
	$(BUF) export $(PROTO_MODULE):$(PROTO_COMMIT) --output proto $(PROTO_PATHS)
	@echo "Synced SDK protos into proto/ at $(PROTO_COMMIT). Run 'make protos' to regenerate."

proto-latest: $(BUF)
	@$(BUF) registry module commit resolve $(PROTO_MODULE):main

# Release gate (needs network): proves proto/ is exactly what the pinned commit exports. CI cannot
# see a hand-patched proto/, because `protos` regenerates consistently from whatever is committed.
check-proto-pin: $(BUF)
	@rm -rf build/proto-pin-check
	@$(BUF) export $(PROTO_MODULE):$(PROTO_COMMIT) --output build/proto-pin-check $(PROTO_PATHS)
	@diff -r proto build/proto-pin-check \
	  && echo "proto/ matches $(PROTO_MODULE):$(PROTO_COMMIT)." \
	  || { echo "proto/ diverges from $(PROTO_COMMIT); re-run 'make sync-protos protos'."; exit 1; }

# Everything generated from proto/.
protos: descriptors typed-track

# The descriptor set the wire and typed-track conformance tests parse with protobuf-java.
descriptors: $(BUF)
	$(BUF) build --exclude-source-info --as-file-descriptor-set -o $(DESCRIPTORS)

# The typed track API (`Pug.track.<event>(…)`, Events, the event classes, the event registry) from the event protos.
typed-track: $(BUF)
	@mkdir -p $(dir $(EVENTS_IMAGE))
	$(BUF) build --path proto/common/events/v1 -o $(EVENTS_IMAGE)
	$(GRADLE) :tools:gen-track:run --args="$(EVENTS_IMAGE) pug/src/main/kotlin"

# CI gate: the committed generated files must match a fresh `make protos`.
check-codegen:
	@drift="$$(git status --porcelain -- $(GENERATED))" || exit 1; \
	test -z "$$drift" \
	  || { git status --short -- $(GENERATED); echo "codegen drift: run 'make protos' and commit"; exit 1; }

# $sdkVersion comes from SDK_VERSION; the published coordinates from GROUP, POM_ARTIFACT_ID and
# VERSION_NAME, which the README's install line names too.
verify-version:
	@gradle_version="$$(sed -n 's/^VERSION_NAME=//p' gradle.properties)"; \
	kotlin_version="$$(sed -n 's/.*SDK_VERSION: String = "\(.*\)"/\1/p' pug/src/main/kotlin/sh/pug/android/Version.kt)"; \
	test "$$gradle_version" = "$$kotlin_version" \
	  || { echo "version mismatch: gradle.properties=$$gradle_version Version.kt=$$kotlin_version"; exit 1; }; \
	coordinates="$$(sed -n 's/^GROUP=//p' gradle.properties):$$(sed -n 's/^POM_ARTIFACT_ID=//p' gradle.properties):$$gradle_version"; \
	grep -qF "\"$$coordinates\"" README.md \
	  || { echo "README.md does not install $$coordinates"; exit 1; }

fmt:
	$(GRADLE) spotlessApply

lint:
	$(GRADLE) spotlessCheck :detekt :pug:lintDebug

test:
	$(GRADLE) :pug:testDebugUnitTest

build:
	$(GRADLE) :pug:assembleRelease

# Rewrites pug/api/pug.api from the release AAR; review the diff before committing it.
abi-dump:
	$(GRADLE) :tools:abi-dump:abiDump

# Rewrites pug/gradle.lockfile, the locked release runtime classpath, after a runtime dependency
# changes; review the diff before committing it.
locks:
	$(GRADLE) :pug:dependencies --configuration releaseRuntimeClasspath --write-locks

# CI gate: the committed lockfile must match a fresh `make locks`. Resolution cannot catch a stale
# one, since Gradle enforces locked versions instead of failing on them.
check-locks:
	@drift="$$(git status --porcelain -- $(LOCKFILE))" || exit 1; \
	test -z "$$drift" \
	  || { git status --short -- $(LOCKFILE); echo "lockfile drift: run 'make locks' and commit"; exit 1; }

# The sample app, debug and minified release.
sample:
	$(GRADLE) :sample:assemble

VERSION := $(shell sed -n 's/^VERSION_NAME=//p' gradle.properties)
BUNDLE := pug/build/central/pug-android-$(VERSION).zip

# The signed Maven Central bundle. Needs ORG_GRADLE_PROJECT_signingInMemoryKey and
# ORG_GRADLE_PROJECT_signingInMemoryKeyPassword; no configuration cache, which would store the key.
publish-bundle: verify-version
	$(GRADLE) --no-configuration-cache :pug:centralBundle
	@echo "Built $(BUNDLE)"

# Uploads the bundle to the Central Portal, where it waits for "Publish" (irreversible) after
# validation. CENTRAL_USERNAME and CENTRAL_PASSWORD are a Portal user token. Pushing a version tag
# runs this in CI (.github/workflows/release.yml).
publish-central:
	@test -n "$$CENTRAL_USERNAME" && test -n "$$CENTRAL_PASSWORD" \
	  || { echo "CENTRAL_USERNAME and CENTRAL_PASSWORD are not set"; exit 1; }
	$(MAKE) publish-bundle
	@curl --fail-with-body --silent --show-error -X POST \
	  -H "Authorization: Bearer $$(printf '%s:%s' "$$CENTRAL_USERNAME" "$$CENTRAL_PASSWORD" | base64 | tr -d '\n')" \
	  -F "bundle=@$(BUNDLE)" \
	  "https://central.sonatype.com/api/v1/publisher/upload?name=pug-android-$(VERSION)&publishingType=USER_MANAGED" \
	  && echo " <- deployment id: release it at https://central.sonatype.com/publishing/deployments"

# One Gradle invocation, so configuration runs once.
check: verify-version
	$(GRADLE) spotlessCheck :detekt :pug:lintDebug :pug:testDebugUnitTest :pug:assembleRelease :tools:abi-dump:abiCheck \
	  :tools:dead-code:deadCodeCheck :sample:assembleRelease

ci: protos check-codegen locks check-locks check
