# sdk-android developer tasks. Mirrors the proto vendoring of sdk-web / sdk-flutter / sdk-node /
# sdk-go / sdk-rust: proto/ is committed, so builds and CI never touch the Buf Schema Registry.

.PHONY: sync-protos proto-latest check-proto-pin protos descriptors typed-track check-codegen verify-version fmt lint test build check ci

# `ci` must run `protos` before `check-codegen` diffs the generated files. Keep everything serial
# even under `make -jN`.
.NOTPARALLEL:

# BSR module + pinned commit that proto/ is vendored from (the same commit as sdk-flutter, sdk-node,
# sdk-go and sdk-rust). To bump: `make proto-latest`, set PROTO_COMMIT, then
# `make sync-protos protos` and review the diff.
PROTO_MODULE := buf.build/pugsh/pug
PROTO_COMMIT := 8b5d0680b10c4693b4d9d15d3dbfc663
# An allowlist: buf adds the transitive imports (buf/validate). sdk/devices is left out on purpose,
# since this SDK does no device registration (PLAN.md §1).
PROTO_PATHS := --path sdk/events/v1 --path sdk/profiles/v1 --path common/events/v1 --path common/v1/property_value.proto

DESCRIPTORS := pug/src/test/resources/pug.binpb
# A JSON image keeps the source comments and names the custom options, which tools/gen-track reads.
EVENTS_IMAGE := build/typed-track/events.json
TYPED_TRACK := $(addprefix pug/src/main/kotlin/sh/pug/android/,TrackNamespace.kt PugEvents.kt internal/event/WellKnownEvents.kt)
GENERATED := $(DESCRIPTORS) $(TYPED_TRACK)

GRADLE := ./gradlew --console=plain

sync-protos:
	@command -v buf >/dev/null || { echo "buf CLI required: https://buf.build/docs/installation"; exit 1; }
	rm -rf proto
	buf export $(PROTO_MODULE):$(PROTO_COMMIT) --output proto $(PROTO_PATHS)
	@echo "Synced SDK protos into proto/ at $(PROTO_COMMIT). Run 'make protos' to regenerate."

proto-latest:
	@buf registry module commit resolve $(PROTO_MODULE):main

# Release gate (needs network): proves proto/ is exactly what the pinned commit exports. CI cannot
# see a hand-patched proto/, because `protos` regenerates consistently from whatever is committed.
check-proto-pin:
	@command -v buf >/dev/null || { echo "buf CLI required: https://buf.build/docs/installation"; exit 1; }
	@rm -rf build/proto-pin-check
	@buf export $(PROTO_MODULE):$(PROTO_COMMIT) --output build/proto-pin-check $(PROTO_PATHS)
	@diff -r proto build/proto-pin-check \
	  && echo "proto/ matches $(PROTO_MODULE):$(PROTO_COMMIT)." \
	  || { echo "proto/ diverges from $(PROTO_COMMIT); re-run 'make sync-protos protos'."; exit 1; }

# Everything generated from proto/.
protos: descriptors typed-track

# The descriptor set the wire and typed-track conformance tests parse with protobuf-java.
descriptors:
	@command -v buf >/dev/null || { echo "buf CLI required: https://buf.build/docs/installation"; exit 1; }
	buf build --exclude-source-info --as-file-descriptor-set -o $(DESCRIPTORS)

# The typed track API (`Pug.track.<event>(…)`, PugEvents, the event registry) from the event protos.
typed-track:
	@command -v buf >/dev/null || { echo "buf CLI required: https://buf.build/docs/installation"; exit 1; }
	@mkdir -p $(dir $(EVENTS_IMAGE))
	buf build --path proto/common/events/v1 -o $(EVENTS_IMAGE)
	$(GRADLE) :tools:gen-track:run --args="$(EVENTS_IMAGE) pug/src/main/kotlin"

# CI gate: the committed generated files must match a fresh `make protos`.
check-codegen:
	@drift="$$(git status --porcelain -- $(GENERATED))" || exit 1; \
	test -z "$$drift" \
	  || { git status --short -- $(GENERATED); echo "codegen drift: run 'make protos' and commit"; exit 1; }

# $sdkVersion comes from SDK_VERSION; the published artifact version from VERSION_NAME.
verify-version:
	@gradle_version="$$(sed -n 's/^VERSION_NAME=//p' gradle.properties)"; \
	kotlin_version="$$(sed -n 's/.*SDK_VERSION: String = "\(.*\)"/\1/p' pug/src/main/kotlin/sh/pug/android/Version.kt)"; \
	test "$$gradle_version" = "$$kotlin_version" \
	  || { echo "version mismatch: gradle.properties=$$gradle_version Version.kt=$$kotlin_version"; exit 1; }

fmt:
	$(GRADLE) spotlessApply

lint:
	$(GRADLE) spotlessCheck :pug:lintDebug

test:
	$(GRADLE) :pug:testDebugUnitTest

build:
	$(GRADLE) :pug:assembleRelease

# One Gradle invocation, so configuration runs once.
check: verify-version
	$(GRADLE) spotlessCheck :pug:lintDebug :pug:testDebugUnitTest :pug:assembleRelease

ci: protos check-codegen check
