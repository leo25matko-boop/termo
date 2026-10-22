"""Prepare audited, pinned go2rtc source for native TermoDom camera transports.

Downloads are permitted only from the primary author's pinned GitHub archive.
No credentials, station URLs, or device data are involved in this build step.
"""
from pathlib import Path
import argparse
import hashlib
import urllib.request
import zipfile

COMMIT = 'b5948cfb25404cc5cb37b166ecaa2dca20b11d4b'
ARCHIVE_SHA256 = '8db2cacfa2e8c5d585d129934b3f09ee3bfca655fdeb8f91383850adacaaaf58'
SOURCE_URL = f'https://codeload.github.com/AlexxIT/go2rtc/zip/{COMMIT}'

def replace_once(path, old, new):
    text = path.read_text(encoding='utf-8')
    if text.count(old) != 1:
        raise RuntimeError(f'Pinned source changed unexpectedly: {path.name}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8', newline='\n')

def prepare(destination, archive_path=None):
    destination = destination.resolve()
    if destination.exists() and any(destination.iterdir()):
        raise RuntimeError('Use a new empty destination for the pinned camera engine source.')
    destination.mkdir(parents=True, exist_ok=True)
    data = archive_path.read_bytes() if archive_path else urllib.request.urlopen(SOURCE_URL, timeout=60).read()
    if hashlib.sha256(data).hexdigest() != ARCHIVE_SHA256:
        raise RuntimeError('Pinned camera source archive SHA256 mismatch.')
    import io
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        prefix = f'go2rtc-{COMMIT}/'
        for entry in archive.infolist():
            if not entry.filename.startswith(prefix):
                raise RuntimeError('Unexpected source archive root.')
            relative = entry.filename[len(prefix):]
            target = (destination / relative).resolve()
            if not target.is_relative_to(destination):
                raise RuntimeError('Source archive path escapes its destination.')
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            elif relative:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(entry))

    # 1. Require RTSP credentials on loopback too. Upstream skips localhost.
    replace_once(destination / 'internal/rtsp/rtsp.go',
                 'if conf.Mod.Username != "" && !conn.RemoteAddr().(*net.TCPAddr).IP.IsLoopback() {',
                 'if conf.Mod.Username != "" {')

    # 2. Xiaomi's two-factor query must contain _flag= and encoded user input.
    replace_once(destination / 'pkg/xiaomi/cloud.go',
                 '"_flag" + c.auth["flag"] + "&ticket=" + ticket + "&trust=false&_json=true"',
                 '"_flag=" + url.QueryEscape(c.auth["flag"]) + "&ticket=" + url.QueryEscape(ticket) + "&trust=false&_json=true"')

    # 3. Anonymous-pipe config and all runtime patches live only in child memory.
    # The host persists just the Xiaomi session map encrypted by Windows DPAPI
    # or Android Keystore. Neither API passwords nor Xiaomi tokens reach disk.
    config = destination / 'internal/app/config.go'
    replace_once(config, '"errors"\n', '"errors"\n\t"encoding/json"\n\t"io"\n\t"net"\n\t"strconv"\n')
    replace_once(config, 'var configMu sync.Mutex', 'var configMu sync.Mutex\nvar memoryConfig []byte')
    replace_once(config, '\t// empty config is OK\n\tb, _ := os.ReadFile(ConfigPath)',
                 '\tif ConfigPath == ":memory:" {\n\t\tb, err := yaml.Patch(memoryConfig, path, value)\n\t\tif err == nil { memoryConfig = b }\n\t\treturn err\n\t}\n\n\t// empty config is OK\n\tb, _ := os.ReadFile(ConfigPath)')
    replace_once(config, '\tfor _, conf := range confs {\n',
                 '\tfor _, conf := range confs {\n\t\tif conf == "-" {\n\t\t\tdata, err := io.ReadAll(io.LimitReader(os.Stdin, (1<<20)+1))\n\t\t\tif err != nil { panic("camera configuration pipe failed") }\n\t\t\tdata, err = decodeTermodomPipeJSON(data)\n\t\t\tif err != nil { panic("camera configuration pipe failed") }\n\t\t\tmemoryConfig = data\n\t\t\tConfigPath = ":memory:"\n\t\t\tconfigs = append(configs, data)\n\t\t\tcontinue\n\t\t}\n')
    replace_once(config, 'if ConfigPath != "" {\n\t\tif !filepath.IsAbs(ConfigPath)',
                 'if ConfigPath != "" && ConfigPath != ":memory:" {\n\t\tif !filepath.IsAbs(ConfigPath)')
    with config.open('a', encoding='utf-8', newline='\n') as output:
        output.write('''

// Android JSONStringer legally escapes / as \\/, which yaml.v3 rejects.
// Parse the private pipe as JSON first and encode YAML-compatible JSON. Invalid or
// incomplete configuration must fail before API/RTSP can bind default ports.
func decodeTermodomPipeJSON(data []byte) ([]byte, error) {
    invalid := errors.New("invalid private camera configuration")
    if len(data) == 0 || len(data) > 1<<20 { return nil, invalid }
    var cfg map[string]any
    if json.Unmarshal(data, &cfg) != nil || len(cfg) == 0 { return nil, invalid }
    apiCfg, ok := cfg["api"].(map[string]any)
    if !ok { return nil, invalid }
    rtspCfg, ok := cfg["rtsp"].(map[string]any)
    if !ok { return nil, invalid }
    apiAddress, apiOK := privateTermodomListener(apiCfg)
    rtspAddress, rtspOK := privateTermodomListener(rtspCfg)
    if !apiOK || !rtspOK || apiAddress == rtspAddress || apiCfg["local_auth"] != true { return nil, invalid }
    paths, ok := apiCfg["allow_paths"].([]any)
    if !ok || len(paths) != 4 { return nil, invalid }
    expected := map[string]bool{"/api":true,"/api/xiaomi":true,"/api/streams":true,"/api/termodom/session":true}
    for _, raw := range paths {
        name, ok := raw.(string)
        if !ok || !expected[name] { return nil, invalid }
        delete(expected, name)
    }
    if len(expected) != 0 { return nil, invalid }
    if origin, exists := apiCfg["origin"]; exists && origin != "" { return nil, invalid }
    logCfg, ok := cfg["log"].(map[string]any)
    if !ok || logCfg["level"] != "disabled" { return nil, invalid }
    // json.Marshal never emits Android's optional slash escapes. Retain flow
    // notation because upstream yaml.Patch expects it for empty stream maps.
    encoded, err := json.Marshal(cfg)
    if err != nil { return nil, invalid }
    return encoded, nil
}

func privateTermodomListener(cfg map[string]any) (string, bool) {
    address, ok := cfg["listen"].(string)
    if !ok { return "", false }
    host, port, err := net.SplitHostPort(address)
    number, numberErr := strconv.Atoi(port)
    username, userOK := cfg["username"].(string)
    password, passOK := cfg["password"].(string)
    return address, err == nil && numberErr == nil && host == "127.0.0.1" && number > 1023 && number <= 65535 &&
        userOK && username != "" && passOK && len(password) >= 16
}
''')

    # 4. Authenticated, allowlisted private JSON session export; never a web UI.
    xiaomi = destination / 'internal/xiaomi/xiaomi.go'
    replace_once(xiaomi, '\tapi.HandleFunc("api/xiaomi", apiXiaomi)',
                 '\tapi.HandleFunc("api/xiaomi", apiXiaomi)\n\tapi.HandleFunc("api/termodom/session", apiTermodomSession)')
    with xiaomi.open('a', encoding='utf-8', newline='\n') as output:
        output.write('''

// TermoDom host reads this through its random, locally authenticated API.
// Tokens stay in process memory until the host encrypts the response at rest.
func apiTermodomSession(w http.ResponseWriter, r *http.Request) {
    if r.Method != "GET" { http.Error(w, "Method not allowed", http.StatusMethodNotAllowed); return }
    cloudsMu.Lock()
    defer cloudsMu.Unlock()
    copy := map[string]string{}
    for id, token := range tokens { copy[id] = token }
    w.Header().Set("Cache-Control", "no-store")
    api.ResponseJSON(w, copy)
}
''')

    # The original all-in-one program imports many unrelated network services.
    # This native transport builds only the required camera API, RTSP and Xiaomi.
    (destination / 'main.go').write_text('''package main

import (
    "github.com/AlexxIT/go2rtc/internal/api"
    "github.com/AlexxIT/go2rtc/internal/app"
    "github.com/AlexxIT/go2rtc/internal/rtsp"
    "github.com/AlexxIT/go2rtc/internal/streams"
    "github.com/AlexxIT/go2rtc/internal/xiaomi"
    "github.com/AlexxIT/go2rtc/pkg/shell"
)

func main() {
    app.Version = "1.9.14-termodom1"
    app.Init()
    api.Init()
    streams.Init()
    rtsp.Init()
    xiaomi.Init()
    shell.RunUntilSignal()
}
''', encoding='utf-8', newline='\n')
    add_tests(destination)
    (destination / 'TERMODOM-PATCHES.txt').write_text(
        'Pinned upstream: ' + COMMIT + '\nArchive SHA256: ' + ARCHIVE_SHA256 + '\n'
        'Changes: authenticated RTSP on loopback; encoded Xiaomi two-factor query; '
        'stdin and memory-only config; authenticated session export; camera-only main.\n', encoding='utf-8')
    print(f'CAMERA_SOURCE_VERIFIED: {COMMIT}; sha256={ARCHIVE_SHA256}; destination={destination}')

def add_tests(destination):
    (destination / 'internal/api/termodom_auth_test.go').write_text('''package api

import (
    "net/http"
    "net/http/httptest"
    "testing"
)

func TestTermodomRequiresLocalAuthentication(t *testing.T) {
    protected := middlewareAuth("synthetic-user", "synthetic-password", true,
        http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
    for _, testcase := range []struct{password string; want int}{
        {"", 401}, {"incorrect", 401}, {"synthetic-password", 204},
    } {
        request := httptest.NewRequest("GET", "http://127.0.0.1/api", nil)
        request.RemoteAddr = "127.0.0.1:10000"
        if testcase.password != "" { request.SetBasicAuth("synthetic-user", testcase.password) }
        result := httptest.NewRecorder()
        protected.ServeHTTP(result, request)
        if result.Code != testcase.want { t.Fatalf("local auth status %d, want %d", result.Code, testcase.want) }
    }
}
''', encoding='utf-8', newline='\n')
    (destination / 'internal/app/termodom_memory_test.go').write_text('''package app

import (
    "encoding/json"
    "os"
    "strings"
    "testing"
    "github.com/AlexxIT/go2rtc/pkg/yaml"
)

func TestTermodomStdinConfigKeepsMemorySentinel(t *testing.T) {
    previousInput, previousPath, previousConfig, previousConfigs := os.Stdin, ConfigPath, memoryConfig, configs
    defer func() { os.Stdin, ConfigPath, memoryConfig, configs = previousInput, previousPath, previousConfig, previousConfigs }()
    reader, writer, err := os.Pipe()
    if err != nil { t.Fatal(err) }
    defer reader.Close()
    if _, err = writer.Write(termomdomTestPipeConfig()); err != nil { t.Fatal(err) }
    writer.Close()
    os.Stdin = reader
    initConfig(flagConfig{"-"})
    if ConfigPath != ":memory:" { t.Fatal("memory config became a disk path") }
}

func TestTermodomRuntimePatchesStayInMemory(t *testing.T) {
    previousPath, previousConfig := ConfigPath, memoryConfig
    defer func() { ConfigPath, memoryConfig = previousPath, previousConfig }()
    ConfigPath = ":memory:"
    memoryConfig = []byte(`{"xiaomi":{},"streams":{}}`)
    if err := PatchConfig([]string{"xiaomi", "synthetic-id"}, "synthetic-token"); err != nil { t.Fatal(err) }
    if !strings.Contains(string(memoryConfig), "synthetic-token") { t.Fatal("session patch was lost") }
    if err := PatchConfig([]string{"streams", "synthetic-stream"}, []string{"xiaomi://synthetic"}); err != nil { t.Fatal(err) }
    if !strings.Contains(string(memoryConfig), "synthetic-stream") { t.Fatal("stream patch was lost") }
}

func termomdomTestPipeConfig() []byte {
    data, _ := json.Marshal(map[string]any{
        "api":map[string]any{"listen":"127.0.0.1:41001","username":"synthetic-user","password":"synthetic-private-password",
            "local_auth":true,"allow_paths":[]string{"/api","/api/xiaomi","/api/streams","/api/termodom/session"}},
        "rtsp":map[string]any{"listen":"127.0.0.1:41002","username":"synthetic-video-user","password":"synthetic-video-password"},
        "log":map[string]any{"level":"disabled"},"xiaomi":map[string]string{},
    })
    return data
}

func TestTermodomAndroidEscapedSlashJSONPreservesSecuritySettings(t *testing.T) {
    original := termomdomTestPipeConfig()
    android := strings.ReplaceAll(string(original), "/", "\\\\/")
    for _, source := range [][]byte{original, []byte(android)} {
        encoded, err := decodeTermodomPipeJSON(source)
        if err != nil { t.Fatal(err) }
        var actual map[string]any
        if err = yaml.Unmarshal(encoded, &actual); err != nil { t.Fatal(err) }
        apiCfg := actual["api"].(map[string]interface{})
        rtspCfg := actual["rtsp"].(map[string]interface{})
        if apiCfg["listen"] != "127.0.0.1:41001" || rtspCfg["listen"] != "127.0.0.1:41002" { t.Fatal("private ports changed") }
        if apiCfg["local_auth"] != true || apiCfg["password"] != "synthetic-private-password" || rtspCfg["password"] != "synthetic-video-password" { t.Fatal("authentication configuration changed") }
        paths := apiCfg["allow_paths"].([]interface{})
        if len(paths) != 4 || paths[0] != "/api" || paths[3] != "/api/termodom/session" { t.Fatal("private API paths changed") }
        tokens := actual["xiaomi"].(map[string]interface{})
        if len(tokens) != 0 { t.Fatal("empty Xiaomi session changed") }
        previousPath, previousConfig := ConfigPath, memoryConfig
        ConfigPath, memoryConfig = ":memory:", encoded
        if err = PatchConfig([]string{"streams", "synthetic-stream"}, []string{"rtsp://127.0.0.1:9/synthetic"}); err != nil { t.Fatal("normalized stream patch failed:",err) }
        if err = PatchConfig([]string{"xiaomi", "synthetic-id"}, "synthetic-token/with/slashes"); err != nil { t.Fatal("normalized session patch failed:",err) }
        ConfigPath, memoryConfig = previousPath, previousConfig
    }
}

func TestTermodomPipeRejectsMalformedOrInsecureConfiguration(t *testing.T) {
    valid := string(termomdomTestPipeConfig())
    invalid := []string{"", "null", "[]", "{}", "not JSON", valid+"{}", valid+strings.Repeat(" ", 1<<20),
        strings.Replace(valid,"127.0.0.1:41001","0.0.0.0:41001",1),
        strings.Replace(valid,"127.0.0.1:41002","127.0.0.1:41001",1),
        strings.Replace(valid,"\\"local_auth\\":true","\\"local_auth\\":false",1),
        strings.Replace(valid,"synthetic-private-password","TOPSECRET",1),
        strings.Replace(valid,"/api/streams","/api/log",1),
        strings.Replace(valid,"disabled","trace",1),
    }
    for _, source := range invalid {
        if _, err := decodeTermodomPipeJSON([]byte(source)); err == nil { t.Fatal("invalid pipe config was accepted") } else if strings.Contains(err.Error(), "TOPSECRET") { t.Fatal("private input leaked through error") }
    }
}
''', encoding='utf-8', newline='\n')
    (destination / 'pkg/xiaomi/termodom_verify_test.go').write_text('''package xiaomi

import (
    "io"
    "net/http"
    "strings"
    "testing"
)

type termodomRoundTrip func(*http.Request) (*http.Response, error)
func (f termodomRoundTrip) RoundTrip(request *http.Request) (*http.Response, error) { return f(request) }

func TestTermodomVerificationQueryEscapesCode(t *testing.T) {
    called := false
    cloud := &Cloud{
        auth: map[string]string{"flag":"8", "identity_session":"synthetic-session"},
        client: &http.Client{Transport: termodomRoundTrip(func(request *http.Request) (*http.Response, error) {
            called = true
            if request.URL.Query().Get("_flag") != "8" { t.Fatal("missing _flag= parameter") }
            if request.URL.Query().Get("ticket") != "a+b&c" { t.Fatal("verification code was not escaped") }
            return &http.Response{StatusCode:200, Header:make(http.Header), Body:io.NopCloser(strings.NewReader(`{"location":""}`))}, nil
        })},
    }
    _ = cloud.LoginWithVerify("a+b&c")
    if !called { t.Fatal("verification request was not sent to fake transport") }
}
''', encoding='utf-8', newline='\n')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--destination', type=Path, required=True)
    parser.add_argument('--archive', type=Path)
    args = parser.parse_args()
    prepare(args.destination, args.archive)
