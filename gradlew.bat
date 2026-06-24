@rem Gradle startup script for Windows
@if "%DEBUG%"=="" @echo off
@rem Set local scope
setlocal
set GRADLE_OPTS=%GRADLE_OPTS% "-Xdx64m" "-Xmx512m"
call gradle %*
endlocal
