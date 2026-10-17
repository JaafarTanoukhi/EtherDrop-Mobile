param([string]$JavaHome = 'C:\Program Files\Java\jdk-21.0.10')
$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $env:ETHERDROP_JAVA_HOME = $JavaHome
    if (-not (Test-Path -LiteralPath '.expo/test-deps/json.jar')) {
        New-Item -ItemType Directory -Path '.expo/test-deps' -Force | Out-Null
        Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar' -OutFile '.expo/test-deps/json.jar'
    }
    & (Join-Path $JavaHome 'bin/javac.exe') -cp '.expo/test-deps/json.jar' -d '.expo/test-classes' 'modules/etherdrop/android/src/main/java/expo/modules/etherdrop/EtherDropEngine.java' 'tests/NativePeerMain.java'
    if ($LASTEXITCODE -ne 0) { throw 'The transfer engine did not compile.' }
    & '../EtherDrop/.venv/Scripts/python.exe' -m unittest discover -s tests -p test_protocol.py -v
    if ($LASTEXITCODE -ne 0) { throw 'Protocol interoperability checks failed.' }
} finally { Pop-Location }
