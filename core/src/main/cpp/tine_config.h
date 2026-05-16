//
// Created by canyie on 2020/8/21.
//

#ifndef TINE_TINE_CONFIG_H
#define TINE_TINE_CONFIG_H

#include "utils/macros.h"

namespace tine {
    class TineConfig final {
    public:
        static bool debug;
        static bool debuggable;
        static bool anti_checks;
        static bool jit_compilation_allowed;
        static bool auto_compile_bridge;
    private:
        DISALLOW_IMPLICIT_CONSTRUCTORS(TineConfig);
    };
}

#endif //TINE_TINE_CONFIG_H
