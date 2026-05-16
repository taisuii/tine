#define FUNCTION(name) \
.data; \
.align 4; \
.global name; \
name:

#define VAR(name) \
.global name; \
name:

FUNCTION(tine_direct_jump_trampoline)
ldr pc, tine_direct_jump_trampoline_jump_entry
VAR(tine_direct_jump_trampoline_jump_entry)
.long 0

FUNCTION(tine_bridge_jump_trampoline)
ldr ip, tine_bridge_jump_trampoline_target_method
cmp r0, ip
ldrne pc, tine_bridge_jump_trampoline_call_origin_entry
ldr ip, tine_bridge_jump_trampoline_extras
str r4, [sp, #-128]

acquire_lock:
ldrex r0, [ip]
cmp r0, #1
wfene // other thread holding the lock, wait it release lock
mov r0, #0
strexeq r4, r0, [ip]
cmpeq r4, #0 // store succeeded?
bne acquire_lock // acquire lock failed, try again
dmb

// Now we hold the lock!
ldr r4, [sp, #-128]
str r1, [ip, #4]
str r2, [ip, #8]
str r3, [ip, #12]
ldr r1, tine_bridge_jump_trampoline_target_method // first param = callee ArtMethod
mov r2, ip // second param = extras (saved r1, r2, r3)
mov r3, sp // third param = sp
ldr r0, tine_bridge_jump_trampoline_bridge_method
ldr pc, tine_bridge_jump_trampoline_bridge_entry
VAR(tine_bridge_jump_trampoline_target_method)
.long 0
VAR(tine_bridge_jump_trampoline_extras)
.long 0
VAR(tine_bridge_jump_trampoline_bridge_method)
.long 0
VAR(tine_bridge_jump_trampoline_bridge_entry)
.long 0
VAR(tine_bridge_jump_trampoline_call_origin_entry)
.long 0

FUNCTION(tine_call_origin_trampoline)
ldr r0, tine_call_origin_trampoline_origin_method
ldr pc, tine_call_origin_trampoline_origin_code_entry
VAR(tine_call_origin_trampoline_origin_method)
.long 0
VAR(tine_call_origin_trampoline_origin_code_entry)
.long 0

FUNCTION(tine_backup_trampoline)
ldr r0, tine_backup_trampoline_origin_method
VAR(tine_backup_trampoline_override_space)
.long 0 // 4 bytes (will be overwritten)
.long 0 // 4 bytes (will be overwritten)
nop // 4 bytes, may be overwritten for anti checks
ldr pc, tine_backup_trampoline_remaining_code_entry
VAR(tine_backup_trampoline_origin_method)
.long 0
VAR(tine_backup_trampoline_remaining_code_entry)
.long 0

FUNCTION(tine_trampolines_end)
// End tag used for calculate size only
