cmake_minimum_required(VERSION 3.13)

include($ENV{PICO_SDK_PATH}/external/pico_sdk_import.cmake)

project(fable_navigation_bridge C CXX ASM)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

pico_sdk_init()

add_executable(fable_navigation_bridge
    main.c
)

target_link_libraries(fable_navigation_bridge
    hardware_i2c
    hardware_uart
    pico_i2c_slave
    pico_stdlib
)

pico_enable_stdio_usb(fable_navigation_bridge 1)
pico_enable_stdio_uart(fable_navigation_bridge 0)
pico_add_extra_outputs(fable_navigation_bridge)
