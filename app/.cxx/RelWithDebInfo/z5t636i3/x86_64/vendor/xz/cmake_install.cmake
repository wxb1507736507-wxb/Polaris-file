# Install script for directory: D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz

# Set the install prefix
if(NOT DEFINED CMAKE_INSTALL_PREFIX)
  set(CMAKE_INSTALL_PREFIX "C:/Program Files (x86)/xe")
endif()
string(REGEX REPLACE "/$" "" CMAKE_INSTALL_PREFIX "${CMAKE_INSTALL_PREFIX}")

# Set the install configuration name.
if(NOT DEFINED CMAKE_INSTALL_CONFIG_NAME)
  if(BUILD_TYPE)
    string(REGEX REPLACE "^[^A-Za-z0-9_]+" ""
           CMAKE_INSTALL_CONFIG_NAME "${BUILD_TYPE}")
  else()
    set(CMAKE_INSTALL_CONFIG_NAME "RelWithDebInfo")
  endif()
  message(STATUS "Install configuration: \"${CMAKE_INSTALL_CONFIG_NAME}\"")
endif()

# Set the component getting installed.
if(NOT CMAKE_INSTALL_COMPONENT)
  if(COMPONENT)
    message(STATUS "Install component: \"${COMPONENT}\"")
    set(CMAKE_INSTALL_COMPONENT "${COMPONENT}")
  else()
    set(CMAKE_INSTALL_COMPONENT)
  endif()
endif()

# Install shared libraries without execute permission?
if(NOT DEFINED CMAKE_INSTALL_SO_NO_EXE)
  set(CMAKE_INSTALL_SO_NO_EXE "0")
endif()

# Is this installation the result of a crosscompile?
if(NOT DEFINED CMAKE_CROSSCOMPILING)
  set(CMAKE_CROSSCOMPILING "TRUE")
endif()

# Set default install directory permissions.
if(NOT DEFINED CMAKE_OBJDUMP)
  set(CMAKE_OBJDUMP "C:/Users/w1870/AppData/Local/Android/Sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-objdump.exe")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xliblzma_Developmentx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib" TYPE STATIC_LIBRARY FILES "D:/360/task/limao-unzip/app/.cxx/RelWithDebInfo/z5t636i3/x86_64/vendor/xz/liblzma.a")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xliblzma_Developmentx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/include" TYPE DIRECTORY FILES "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/src/liblzma/api/" FILES_MATCHING REGEX "/[^/]*\\.h$")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xliblzma_Developmentx" OR NOT CMAKE_INSTALL_COMPONENT)
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/liblzma/liblzma-targets.cmake")
    file(DIFFERENT EXPORT_FILE_CHANGED FILES
         "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/liblzma/liblzma-targets.cmake"
         "D:/360/task/limao-unzip/app/.cxx/RelWithDebInfo/z5t636i3/x86_64/vendor/xz/CMakeFiles/Export/lib/cmake/liblzma/liblzma-targets.cmake")
    if(EXPORT_FILE_CHANGED)
      file(GLOB OLD_CONFIG_FILES "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/liblzma/liblzma-targets-*.cmake")
      if(OLD_CONFIG_FILES)
        message(STATUS "Old export file \"$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/lib/cmake/liblzma/liblzma-targets.cmake\" will be replaced.  Removing files [${OLD_CONFIG_FILES}].")
        file(REMOVE ${OLD_CONFIG_FILES})
      endif()
    endif()
  endif()
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/liblzma" TYPE FILE FILES "D:/360/task/limao-unzip/app/.cxx/RelWithDebInfo/z5t636i3/x86_64/vendor/xz/CMakeFiles/Export/lib/cmake/liblzma/liblzma-targets.cmake")
  if("${CMAKE_INSTALL_CONFIG_NAME}" MATCHES "^([Rr][Ee][Ll][Ww][Ii][Tt][Hh][Dd][Ee][Bb][Ii][Nn][Ff][Oo])$")
    file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/liblzma" TYPE FILE FILES "D:/360/task/limao-unzip/app/.cxx/RelWithDebInfo/z5t636i3/x86_64/vendor/xz/CMakeFiles/Export/lib/cmake/liblzma/liblzma-targets-relwithdebinfo.cmake")
  endif()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xliblzma_Developmentx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/liblzma" TYPE FILE FILES
    "D:/360/task/limao-unzip/app/.cxx/RelWithDebInfo/z5t636i3/x86_64/vendor/xz/liblzma-config.cmake"
    "D:/360/task/limao-unzip/app/.cxx/RelWithDebInfo/z5t636i3/x86_64/vendor/xz/liblzma-config-version.cmake"
    )
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xliblzma_Developmentx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/pkgconfig" TYPE FILE FILES "D:/360/task/limao-unzip/app/.cxx/RelWithDebInfo/z5t636i3/x86_64/vendor/xz/liblzma.pc")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xxzdec_Runtimex" OR NOT CMAKE_INSTALL_COMPONENT)
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/xzdec" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/xzdec")
    file(RPATH_CHECK
         FILE "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/xzdec"
         RPATH "")
  endif()
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/bin" TYPE EXECUTABLE FILES "D:/360/task/limao-unzip/app/build/intermediates/cxx/RelWithDebInfo/z5t636i3/obj/x86_64/xzdec")
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/xzdec" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/xzdec")
    if(CMAKE_INSTALL_DO_STRIP)
      execute_process(COMMAND "C:/Users/w1870/AppData/Local/Android/Sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/xzdec")
    endif()
  endif()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xlzmadec_Runtimex" OR NOT CMAKE_INSTALL_COMPONENT)
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/lzmadec" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/lzmadec")
    file(RPATH_CHECK
         FILE "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/lzmadec"
         RPATH "")
  endif()
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/bin" TYPE EXECUTABLE FILES "D:/360/task/limao-unzip/app/build/intermediates/cxx/RelWithDebInfo/z5t636i3/obj/x86_64/lzmadec")
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/lzmadec" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/lzmadec")
    if(CMAKE_INSTALL_DO_STRIP)
      execute_process(COMMAND "C:/Users/w1870/AppData/Local/Android/Sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/lzmadec")
    endif()
  endif()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xxzdec_Documentationx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/share/man/man1" TYPE FILE FILES "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/src/xzdec/xzdec.1")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xxzdec_Documentationx" OR NOT CMAKE_INSTALL_COMPONENT)
  set(D "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/share/man//man1")
                 foreach(L lzmadec)
                     file(CREATE_LINK "xzdec.1"
                                      "${D}/${L}.1"
                                      SYMBOLIC)
                 endforeach()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xlzmainfo_Runtimex" OR NOT CMAKE_INSTALL_COMPONENT)
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/lzmainfo" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/lzmainfo")
    file(RPATH_CHECK
         FILE "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/lzmainfo"
         RPATH "")
  endif()
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/bin" TYPE EXECUTABLE FILES "D:/360/task/limao-unzip/app/build/intermediates/cxx/RelWithDebInfo/z5t636i3/obj/x86_64/lzmainfo")
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/lzmainfo" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/lzmainfo")
    if(CMAKE_INSTALL_DO_STRIP)
      execute_process(COMMAND "C:/Users/w1870/AppData/Local/Android/Sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/lzmainfo")
    endif()
  endif()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xlzmainfo_Documentationx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/share/man/man1" TYPE FILE FILES "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/src/lzmainfo/lzmainfo.1")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xlzmainfo_Documentationx" OR NOT CMAKE_INSTALL_COMPONENT)
  set(D "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/share/man//man1")
                 foreach(L )
                     file(CREATE_LINK "lzmainfo.1"
                                      "${D}/${L}.1"
                                      SYMBOLIC)
                 endforeach()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xxz_Runtimex" OR NOT CMAKE_INSTALL_COMPONENT)
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/xz" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/xz")
    file(RPATH_CHECK
         FILE "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/xz"
         RPATH "")
  endif()
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/bin" TYPE EXECUTABLE FILES "D:/360/task/limao-unzip/app/build/intermediates/cxx/RelWithDebInfo/z5t636i3/obj/x86_64/xz")
  if(EXISTS "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/xz" AND
     NOT IS_SYMLINK "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/xz")
    if(CMAKE_INSTALL_DO_STRIP)
      execute_process(COMMAND "C:/Users/w1870/AppData/Local/Android/Sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin/xz")
    endif()
  endif()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xxz_Runtimex" OR NOT CMAKE_INSTALL_COMPONENT)
  set(D "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin")
                 foreach(L unxz;xzcat;lzma;unlzma;lzcat)
                     file(CREATE_LINK "xz"
                                      "${D}/${L}"
                                      SYMBOLIC)
                 endforeach()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xxz_Documentationx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/share/man/man1" TYPE FILE FILES "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/src/xz/xz.1")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xxz_Documentationx" OR NOT CMAKE_INSTALL_COMPONENT)
  set(D "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/share/man//man1")
                 foreach(L unxz;xzcat;lzma;unlzma;lzcat)
                     file(CREATE_LINK "xz.1"
                                      "${D}/${L}.1"
                                      SYMBOLIC)
                 endforeach()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xscripts_Runtimex" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/bin" TYPE PROGRAM FILES "D:/360/task/limao-unzip/app/.cxx/RelWithDebInfo/z5t636i3/x86_64/vendor/xz/xzdiff")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xscripts_Runtimex" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/bin" TYPE PROGRAM FILES "D:/360/task/limao-unzip/app/.cxx/RelWithDebInfo/z5t636i3/x86_64/vendor/xz/xzgrep")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xscripts_Runtimex" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/bin" TYPE PROGRAM FILES "D:/360/task/limao-unzip/app/.cxx/RelWithDebInfo/z5t636i3/x86_64/vendor/xz/xzmore")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xscripts_Runtimex" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/bin" TYPE PROGRAM FILES "D:/360/task/limao-unzip/app/.cxx/RelWithDebInfo/z5t636i3/x86_64/vendor/xz/xzless")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xscripts_Runtimex" OR NOT CMAKE_INSTALL_COMPONENT)
  set(D "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin")
                 foreach(L xzcmp;lzdiff;lzcmp)
                     file(CREATE_LINK "xzdiff"
                                      "${D}/${L}"
                                      SYMBOLIC)
                 endforeach()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xscripts_Runtimex" OR NOT CMAKE_INSTALL_COMPONENT)
  set(D "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin")
                 foreach(L xzegrep;xzfgrep;lzgrep;lzegrep;lzfgrep)
                     file(CREATE_LINK "xzgrep"
                                      "${D}/${L}"
                                      SYMBOLIC)
                 endforeach()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xscripts_Runtimex" OR NOT CMAKE_INSTALL_COMPONENT)
  set(D "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin")
                 foreach(L lzmore)
                     file(CREATE_LINK "xzmore"
                                      "${D}/${L}"
                                      SYMBOLIC)
                 endforeach()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xscripts_Runtimex" OR NOT CMAKE_INSTALL_COMPONENT)
  set(D "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/bin")
                 foreach(L lzless)
                     file(CREATE_LINK "xzless"
                                      "${D}/${L}"
                                      SYMBOLIC)
                 endforeach()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xscripts_Documentationx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/share/man/man1" TYPE FILE FILES "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/src/scripts/xzdiff.1")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xscripts_Documentationx" OR NOT CMAKE_INSTALL_COMPONENT)
  set(D "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/share/man//man1")
                 foreach(L xzcmp;lzdiff;lzcmp)
                     file(CREATE_LINK "xzdiff.1"
                                      "${D}/${L}.1"
                                      SYMBOLIC)
                 endforeach()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xscripts_Documentationx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/share/man/man1" TYPE FILE FILES "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/src/scripts/xzgrep.1")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xscripts_Documentationx" OR NOT CMAKE_INSTALL_COMPONENT)
  set(D "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/share/man//man1")
                 foreach(L xzegrep;xzfgrep;lzgrep;lzegrep;lzfgrep)
                     file(CREATE_LINK "xzgrep.1"
                                      "${D}/${L}.1"
                                      SYMBOLIC)
                 endforeach()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xscripts_Documentationx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/share/man/man1" TYPE FILE FILES "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/src/scripts/xzmore.1")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xscripts_Documentationx" OR NOT CMAKE_INSTALL_COMPONENT)
  set(D "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/share/man//man1")
                 foreach(L lzmore)
                     file(CREATE_LINK "xzmore.1"
                                      "${D}/${L}.1"
                                      SYMBOLIC)
                 endforeach()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xscripts_Documentationx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/share/man/man1" TYPE FILE FILES "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/src/scripts/xzless.1")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xscripts_Documentationx" OR NOT CMAKE_INSTALL_COMPONENT)
  set(D "$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/share/man//man1")
                 foreach(L lzless)
                     file(CREATE_LINK "xzless.1"
                                      "${D}/${L}.1"
                                      SYMBOLIC)
                 endforeach()
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xliblzma_Documentationx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/share/doc/xz" TYPE DIRECTORY FILES "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/doc/examples")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xDocumentationx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/share/doc/xz" TYPE FILE FILES
    "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/AUTHORS"
    "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/COPYING"
    "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/COPYING.0BSD"
    "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/COPYING.GPLv2"
    "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/NEWS"
    "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/README"
    "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/THANKS"
    "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/doc/faq.txt"
    "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/doc/history.txt"
    "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/doc/lzma-file-format.txt"
    "D:/360/task/limao-unzip/app/src/main/cpp/vendor/xz/doc/xz-file-format.txt"
    )
endif()

