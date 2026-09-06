use std::{fs, sync::Arc};

use compukter_vm::{
    verify_artifact, AdvanceOutcome, ArtifactLimits, CapabilityBinding, EntryArgumentLimits,
    EntryValue, ExecutionProfile, GuestTrap, HostResponse, HostValueInput, HostValueType,
    HostValueView, OperationSchema, RequestId, Session,
};

#[test]
#[ignore = "requires a generated Kotlin executable artifact"]
fn pinned_vm_verifies_kotlin_executable_instruction_artifact() {
    let path = std::env::var("COMPUKTER_KOTLIN_EXECUTABLE_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_EXECUTABLE_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("Kotlin writer output must exist");

    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must decode and verify Kotlin writer output");

    assert_eq!(verified.module_count(), 2);
}

fn entry_argument_limits() -> EntryArgumentLimits {
    EntryArgumentLimits {
        maximum_count: 64,
        maximum_code_units_per_argument: 4096,
        maximum_total_code_units: 16_384,
    }
}

#[test]
#[ignore = "requires a generated Kotlin Int-loops artifact"]
fn k2_int_loops_execute_across_quota_slices_without_host_io() {
    let path = std::env::var("COMPUKTER_KOTLIN_INT_LOOPS_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_INT_LOOPS_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 Int loops output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 Int loops output");
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: 64,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[]).expect("K2 Int loops must admit");
    session.start(&[]).expect("K2 Int loops must start");
    let mut exhausted_slices = 0;

    loop {
        match session.advance(64, 16).expect("K2 Int loops must advance") {
            AdvanceOutcome::SliceExhausted => exhausted_slices += 1,
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 Int loops outcome: {outcome:?}"),
        }
    }

    assert!(
        exhausted_slices > 0,
        "long Int loop must cross a quota boundary"
    );
}

#[test]
#[ignore = "requires a generated Kotlin platform-scalar artifact"]
fn k2_platform_scalar_precondition_traps_before_publishing_a_value() {
    let path = std::env::var("COMPUKTER_KOTLIN_PLATFORM_SCALAR_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_PLATFORM_SCALAR_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 platform-scalar output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 platform-scalar output");
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session =
        Session::admit(verified, profile, &[]).expect("platform-scalar artifact must admit");
    session
        .start(&[])
        .expect("platform-scalar artifact must start");

    loop {
        match session
            .advance(64, 64)
            .expect("platform-scalar artifact must advance")
        {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Crashed(GuestTrap::DivisionByZero) => break,
            AdvanceOutcome::HostRequestBatch(_) => {
                panic!("invalid platform scalar construction must trap before a host request")
            }
            outcome => panic!("unexpected platform-scalar precondition outcome: {outcome:?}"),
        }
    }
}

#[test]
#[ignore = "requires a generated Kotlin argv artifact"]
fn k2_string_array_entry_executes_exact_utf16_arguments() {
    let path = std::env::var("COMPUKTER_KOTLIN_ARGV_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_ARGV_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 argv output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 argv output");
    let string_argument = [HostValueType::String];
    let no_arguments = [];
    let operations = [
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(
            &[HostValueType::I32, HostValueType::I32],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(&[HostValueType::Bool], HostValueType::Unit),
        OperationSchema::synchronous(
            &[HostValueType::I32, HostValueType::I32],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(
            &[
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::String,
            ],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(
            &[
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::Char,
            ],
            HostValueType::Unit,
        ),
    ];
    let binding = CapabilityBinding::new("compukter", "terminal", 2, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[binding]).expect("K2 argv must admit");
    let arguments = [
        Vec::<u16>::new().into_boxed_slice(),
        vec![0x0041, 0x0000, 0xd800, 0x0042].into_boxed_slice(),
    ];
    session
        .start(&[EntryValue::StringArray(&arguments)])
        .expect("K2 argv must start");

    let request_id = loop {
        match session.advance(64, 64).expect("K2 argv must advance") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::HostRequestBatch(batch) => {
                let request = batch.get(0).expect("K2 argv must publish one request");
                assert_eq!(0, request.operation());
                assert_eq!(
                    Some(HostValueView::String(&[
                        0x003a, 0x0041, 0x0000, 0xd800, 0x0042
                    ])),
                    request.arguments().get(0),
                );
                break request.id();
            }
            outcome => panic!("unexpected K2 argv outcome before terminal write: {outcome:?}"),
        }
    };
    session
        .resume(request_id, HostResponse::Success(HostValueInput::Unit))
        .expect("terminal write must resume");
    loop {
        match session.advance(64, 64).expect("K2 argv must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 argv outcome after terminal write: {outcome:?}"),
        }
    }
}

#[test]
#[ignore = "requires a generated Kotlin subset artifact"]
fn k2_string_materialization_executes_char_arrays_and_scalar_templates() {
    let path = std::env::var("COMPUKTER_KOTLIN_SUBSET_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_SUBSET_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 subset output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 subset output");
    let string_argument = [HostValueType::String];
    let no_arguments = [];
    let operations = [
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(
            &[HostValueType::I32, HostValueType::I32],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(&[HostValueType::Bool], HostValueType::Unit),
        OperationSchema::synchronous(
            &[HostValueType::I32, HostValueType::I32],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(
            &[
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::String,
            ],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(
            &[
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::Char,
            ],
            HostValueType::Unit,
        ),
    ];
    let binding = CapabilityBinding::new("compukter", "terminal", 2, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session = Session::admit(verified, profile, &[binding]).expect("K2 subset must admit");
    session.start(&[]).expect("K2 subset must start");

    let request_id = loop {
        match session.advance(64, 64).expect("K2 subset must advance") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::HostRequestBatch(batch) => {
                let request = batch.get(0).expect("K2 subset must publish one request");
                assert_eq!("compukter", request.namespace());
                assert_eq!("terminal", request.name());
                assert_eq!(0, request.operation());
                assert_eq!(
                    Some(HostValueView::String(&[0x41, 0xd83d, 0xde00, 0x5a])),
                    request.arguments().get(0),
                );
                break request.id();
            }
            outcome => panic!("unexpected K2 subset outcome before terminal write: {outcome:?}"),
        }
    };
    session
        .resume(request_id, HostResponse::Success(HostValueInput::Unit))
        .expect("terminal write must resume");
    let template_request = next_host_request(
        &mut session,
        "scalar template write",
        0,
        Some(&utf16("2/true/x/15")),
    );
    session
        .resume(
            template_request,
            HostResponse::Success(HostValueInput::Unit),
        )
        .expect("scalar template write must resume");
    loop {
        match session.advance(64, 64).expect("K2 subset must finish") {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 subset outcome after terminal write: {outcome:?}"),
        }
    }
}

#[test]
#[ignore = "requires a generated Kotlin suspend-call artifact"]
fn k2_suspend_project_call_resumes_across_async_capability() {
    let path = std::env::var("COMPUKTER_KOTLIN_SUSPEND_CALL_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_SUSPEND_CALL_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 suspend-call output must exist");
    let verified = verify_artifact(Arc::from(bytes), ArtifactLimits::default())
        .expect("pinned VM must verify K2 suspend-call output");
    let string_argument = [HostValueType::String];
    let no_arguments = [];
    let operations = [
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::asynchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::String),
        OperationSchema::synchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(
            &[HostValueType::I32, HostValueType::I32],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(&[HostValueType::Bool], HostValueType::Unit),
        OperationSchema::synchronous(
            &[HostValueType::I32, HostValueType::I32],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(
            &[
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::String,
            ],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(
            &[
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::Char,
            ],
            HostValueType::Unit,
        ),
    ];
    let binding = CapabilityBinding::new("compukter", "terminal", 2, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session =
        Session::admit(verified, profile, &[binding]).expect("K2 suspend-call program must admit");
    session
        .start(&[])
        .expect("K2 suspend-call program must start");

    let await_request = next_host_request(&mut session, "awaitEvent", 3, None);
    session
        .resume(await_request, HostResponse::Success(HostValueInput::I32(1)))
        .expect("awaitEvent must resume");

    let key_request = next_host_request(&mut session, "eventKey", 5, None);
    session
        .resume(key_request, HostResponse::Success(HostValueInput::I32(13)))
        .expect("eventKey must resume");

    let write_request = next_host_request(
        &mut session,
        "write",
        0,
        Some(&[0x65, 0x6e, 0x74, 0x65, 0x72]),
    );
    session
        .resume(write_request, HostResponse::Success(HostValueInput::Unit))
        .expect("write must resume");

    loop {
        match session
            .advance(64, 64)
            .expect("K2 suspend-call program must finish")
        {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 suspend-call outcome after write: {outcome:?}"),
        }
    }
}

#[test]
#[ignore = "requires a generated Kotlin when artifact"]
fn k2_bounded_when_selects_matched_and_fallback_branches() {
    let path = std::env::var("COMPUKTER_KOTLIN_WHEN_ARTIFACT")
        .expect("COMPUKTER_KOTLIN_WHEN_ARTIFACT must be set for this conformance test");
    let bytes = fs::read(path).expect("K2 when output must exist");

    assert_eq!(utf16("enter"), execute_when_artifact(&bytes, 13));
    assert_eq!(utf16("other"), execute_when_artifact(&bytes, 99));
}

fn execute_when_artifact(bytes: &[u8], key: i32) -> Vec<u16> {
    let verified = verify_artifact(Arc::from(bytes.to_vec()), ArtifactLimits::default())
        .expect("pinned VM must verify K2 when output");
    let string_argument = [HostValueType::String];
    let no_arguments = [];
    let operations = [
        OperationSchema::synchronous(&string_argument, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::asynchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::String),
        OperationSchema::synchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::I32),
        OperationSchema::synchronous(&no_arguments, HostValueType::Unit),
        OperationSchema::synchronous(
            &[HostValueType::I32, HostValueType::I32],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(&[HostValueType::Bool], HostValueType::Unit),
        OperationSchema::synchronous(
            &[HostValueType::I32, HostValueType::I32],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(
            &[
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::String,
            ],
            HostValueType::Unit,
        ),
        OperationSchema::synchronous(
            &[
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::I32,
                HostValueType::Char,
            ],
            HostValueType::Unit,
        ),
    ];
    let binding = CapabilityBinding::new("compukter", "terminal", 2, 0, &operations);
    let profile = ExecutionProfile {
        heap_bytes: 1024 * 1024,
        frame_storage_bytes: 1024 * 1024,
        maximum_call_depth: 64,
        maximum_coroutines: 1,
        maximum_host_requests: 64,
        maximum_events: 0,
        maximum_slice_budget: u32::MAX,
        compiler_abi: [0; 32],
        platform_abi: [0; 32],
        maximum_host_arguments: 16,
        maximum_outbound_utf16_code_units: 4096,
        maximum_inbound_utf16_code_units: 4096,
        maximum_accepted_responses: 64,
        entry_argument_limits: entry_argument_limits(),
    };
    let mut session =
        Session::admit(verified, profile, &[binding]).expect("K2 when program must admit");
    session.start(&[]).expect("K2 when program must start");

    let await_request = next_host_request(&mut session, "awaitEvent", 3, None);
    session
        .resume(await_request, HostResponse::Success(HostValueInput::I32(1)))
        .expect("awaitEvent must resume");
    let key_request = next_host_request(&mut session, "eventKey", 5, None);
    session
        .resume(key_request, HostResponse::Success(HostValueInput::I32(key)))
        .expect("eventKey must resume");
    let (write_request, output) = next_string_host_request(&mut session, "write", 0);
    session
        .resume(write_request, HostResponse::Success(HostValueInput::Unit))
        .expect("write must resume");
    loop {
        match session
            .advance(64, 64)
            .expect("K2 when program must finish")
        {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::Halted(None) => break,
            outcome => panic!("unexpected K2 when outcome after write: {outcome:?}"),
        }
    }
    output
}

fn next_string_host_request(
    session: &mut Session,
    operation_name: &str,
    expected_operation: u32,
) -> (RequestId, Vec<u16>) {
    loop {
        match session
            .advance(64, 64)
            .unwrap_or_else(|error| panic!("K2 program failed before {operation_name}: {error:?}"))
        {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::HostRequestBatch(batch) => {
                let request = batch.get(0).expect("K2 program must publish one request");
                assert_eq!(expected_operation, request.operation(), "{operation_name}");
                let value = match request.arguments().get(0) {
                    Some(HostValueView::String(value)) => value.to_vec(),
                    argument => panic!("unexpected {operation_name} argument: {argument:?}"),
                };
                return (request.id(), value);
            }
            outcome => panic!("unexpected K2 program outcome before {operation_name}: {outcome:?}"),
        }
    }
}

fn utf16(value: &str) -> Vec<u16> {
    value.encode_utf16().collect()
}

fn next_host_request(
    session: &mut Session,
    operation_name: &str,
    expected_operation: u32,
    expected_string: Option<&[u16]>,
) -> RequestId {
    loop {
        match session
            .advance(64, 64)
            .unwrap_or_else(|error| panic!("K2 program failed before {operation_name}: {error:?}"))
        {
            AdvanceOutcome::SliceExhausted => {}
            AdvanceOutcome::HostRequestBatch(batch) => {
                let request = batch.get(0).expect("K2 program must publish one request");
                assert_eq!(expected_operation, request.operation(), "{operation_name}");
                if let Some(expected) = expected_string {
                    assert_eq!(
                        Some(HostValueView::String(expected)),
                        request.arguments().get(0),
                        "{operation_name}",
                    );
                }
                return request.id();
            }
            outcome => panic!("unexpected K2 program outcome before {operation_name}: {outcome:?}"),
        }
    }
}
