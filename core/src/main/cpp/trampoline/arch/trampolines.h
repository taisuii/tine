//
// Created by canyie on 2020/3/21.
//

#ifndef PINE_TRAMPOLINES_H
#define PINE_TRAMPOLINES_H

extern "C" {

#ifndef __i386__
#ifdef __arm__
void tine_thumb_direct_jump_trampoline();
void tine_thumb_direct_jump_trampoline_jump_entry();

void tine_thumb_bridge_jump_trampoline();
void tine_thumb_bridge_jump_trampoline_target_method();
void tine_thumb_bridge_jump_trampoline_extras();
void tine_thumb_bridge_jump_trampoline_bridge_method();
void tine_thumb_bridge_jump_trampoline_bridge_entry();
void tine_thumb_bridge_jump_trampoline_call_origin_entry();

void tine_thumb_method_jump_trampoline();
void tine_thumb_method_jump_trampoline_dest_method();
void tine_thumb_method_jump_trampoline_dest_entry();

void tine_thumb_call_origin_trampoline();
void tine_thumb_call_origin_trampoline_origin_method();
void tine_thumb_call_origin_trampoline_origin_code_entry();

void tine_thumb_backup_trampoline();
void tine_thumb_backup_trampoline_origin_method();
void tine_thumb_backup_trampoline_override_space();
void tine_thumb_backup_trampoline_remaining_code_entry();

void tine_thumb_trampolines_end();
#else
void tine_direct_jump_trampoline();
void tine_direct_jump_trampoline_jump_entry();

void tine_bridge_jump_trampoline();
void tine_bridge_jump_trampoline_target_method();
void tine_bridge_jump_trampoline_extras();
void tine_bridge_jump_trampoline_bridge_method();
void tine_bridge_jump_trampoline_bridge_entry();
void tine_bridge_jump_trampoline_call_origin_entry();

void tine_method_jump_trampoline();
void tine_method_jump_trampoline_dest_method();
void tine_method_jump_trampoline_dest_entry();

void tine_call_origin_trampoline();
void tine_call_origin_trampoline_origin_method();
void tine_call_origin_trampoline_origin_code_entry();

void tine_backup_trampoline();
void tine_backup_trampoline_origin_method();
void tine_backup_trampoline_override_space();
void tine_backup_trampoline_remaining_code_entry();

void tine_trampolines_end();
#endif
#endif
};

#endif //PINE_TRAMPOLINES_H
