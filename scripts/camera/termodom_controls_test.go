package xiaomi

import (
	"encoding/json"
	"errors"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/AlexxIT/go2rtc/internal/streams"
	"github.com/AlexxIT/go2rtc/pkg/core"
)

const syntheticCameraSchema = `{"type":"urn:miot-spec-v2:device:camera:0000A01C:test-camera:1","services":[
{"iid":47,"type":"urn:miot-spec-v2:service:camera-control:0000782F:test-camera:1","properties":[
{"iid":77,"type":"urn:miot-spec-v2:property:on:00000006:test-camera:1","format":"bool","access":["read","write"]},
{"iid":78,"type":"urn:miot-spec-v2:property:night-shot:00000057:test-camera:1","format":"uint8","access":["read","write"],"value-list":[{"value":9,"description":"Off"},{"value":3,"description":"Auto"}]},
{"iid":79,"type":"urn:miot-spec-v2:property:image-rollover:00000058:test-camera:1","format":"uint16","access":["read","write"],"value-range":[0,180,180]},
{"iid":80,"type":"urn:miot-spec-v2:property:factory-reset:00000099:test-camera:1","format":"bool","access":["read","write"]}]},
{"iid":48,"type":"urn:miot-spec-v2:service:motion-detection:0000784F:test-camera:1","properties":[
{"iid":8,"type":"urn:miot-spec-v2:property:motion-detection-start-time:00000091:test-camera:1","format":"string","access":["read","write"]}]},
{"iid":49,"type":"urn:miot-spec-v2:service:memory-card-management:0000784E:test-camera:1","properties":[
{"iid":1,"type":"urn:miot-spec-v2:property:status:00000007:test-camera:1","format":"uint8","access":["read","write"],"value-list":[{"value":0,"description":"Normal"}]}]}]}`

func TestTermodomControlsUseModelSchemaAndNeverInventTracking(t *testing.T) {
	controls, err := parseControlProfile([]byte(syntheticCameraSchema))
	if err != nil || len(controls) != 5 {
		t.Fatalf("schema controls failed: %d %v", len(controls), err)
	}
	if controls[0].siid != 47 || controls[0].piid != 77 || controls[0].ID != "camera-on" {
		t.Fatal("property IDs were guessed")
	}
	for _, c := range controls {
		if c.ID == "motion-tracking" || c.ID == "factory-reset" {
			t.Fatal("unavailable or dangerous control invented")
		}
	}
	if validControlValue(controls[1], float64(0)) || !validControlValue(controls[1], float64(3)) {
		t.Fatal("enum values did not come from model schema")
	}
	if controls[4].canWrite {
		t.Fatal("SD status became writable")
	}
}
func TestTermodomControlValueValidation(t *testing.T) {
	controls, _ := parseControlProfile([]byte(syntheticCameraSchema))
	if !validControlValue(controls[0], false) || validControlValue(controls[0], float64(0)) {
		t.Fatal("bool was coerced")
	}
	for _, v := range []any{float64(-180), float64(1), float64(90), float64(360), "180", nil} {
		if validControlValue(controls[2], v) {
			t.Fatal("invalid step/range was accepted")
		}
	}
	if !validControlValue(controls[2], float64(180)) {
		t.Fatal("valid rollover rejected")
	}
	for _, s := range []string{"24:00", "12:60", "8:30", "0830", "08:30:00", "whatever"} {
		if validControlValue(controls[3], s) {
			t.Fatal("unknown wire time was accepted")
		}
	}
	if !validControlValue(controls[3], "08:30") {
		t.Fatal("strict time rejected")
	}
}
func setupControlTest(t *testing.T) {
	oldRequest, oldFetch, oldProfiles, oldTokens := controlCloudRequest, controlFetch, controlProfiles, tokens
	t.Cleanup(func() {
		controlCloudRequest = oldRequest
		controlFetch = oldFetch
		controlProfiles = oldProfiles
		tokens = oldTokens
		streams.Delete("synthetic-control")
	})
	controlProfiles = map[string]cachedControlProfile{}
	tokens = map[string]string{"synthetic-account": "not-a-real-token"}
	streams.HandleFunc("xiaomi", func(string) (core.Producer, error) { return nil, errors.New("tests never connect") })
	if _, err := streams.Patch("synthetic-control", "xiaomi://synthetic-account:de@127.0.0.1?did=synthetic-device&model=test.camera.fixture"); err != nil {
		t.Fatal(err)
	}
	controlFetch = func(path string) ([]byte, error) {
		if strings.HasPrefix(path, "instances?") {
			return []byte(`{"instances":[{"model":"test.camera.fixture","version":1,"type":"urn:miot-spec-v2:device:camera:0000A01C:test-camera:1"}]}`), nil
		}
		return []byte(syntheticCameraSchema), nil
	}
}
func ownershipFixture() []byte {
	return []byte(`{"list":[{"did":"synthetic-device","model":"test.camera.fixture"}]}`)
}
func TestTermodomControlsRequireActualReadBeforeWriteAndFalseIsSent(t *testing.T) {
	setupControlTest(t)
	writes, reads := 0, 0
	controlCloudRequest = func(user, region, path, params string) ([]byte, error) {
		if user != "synthetic-account" || region != "de" {
			t.Fatal("wrong account or region")
		}
		switch path {
		case "/v2/home/device_list_page":
			return ownershipFixture(), nil
		case "/miotspec/prop/get":
			reads++
			value := "true"
			if reads > 1 {
				value = "false"
			}
			return []byte(`[{"did":"synthetic-device","siid":47,"piid":77,"code":0,"value":` + value + `}]`), nil
		case "/miotspec/prop/set":
			writes++
			var body struct {
				Params []map[string]any `json:"params"`
			}
			if json.Unmarshal([]byte(params), &body) != nil || len(body.Params) != 1 {
				t.Fatal("invalid write")
			}
			value, exists := body.Params[0]["value"]
			if !exists || value != false || body.Params[0]["siid"] != float64(47) || body.Params[0]["piid"] != float64(77) {
				t.Fatal("false omitted or IDs guessed")
			}
			return []byte(`[{"did":"synthetic-device","siid":47,"piid":77,"code":0}]`), nil
		}
		t.Fatal("arbitrary cloud endpoint used")
		return nil, nil
	}
	w := httptest.NewRecorder()
	r := httptest.NewRequest("POST", "/api/termodom/xiaomi", strings.NewReader(`{"src":"synthetic-control","id":"camera-on","value":false}`))
	apiTermodomControls(w, r)
	if w.Code != 200 || writes != 1 || reads != 2 || !strings.Contains(w.Body.String(), `"confirmed":true`) {
		t.Fatalf("one set/readback failed: %d %d %d %s", w.Code, writes, reads, w.Body.String())
	}
}
func TestTermodomControlsFailedReadNeverSendsWrite(t *testing.T) {
	setupControlTest(t)
	writes := 0
	controlCloudRequest = func(_, _, path, _ string) ([]byte, error) {
		switch path {
		case "/v2/home/device_list_page":
			return ownershipFixture(), nil
		case "/miotspec/prop/get":
			return []byte(`[{"did":"synthetic-device","siid":47,"piid":77,"code":-1,"value":true}]`), nil
		case "/miotspec/prop/set":
			writes++
			return nil, nil
		}
		return nil, errors.New("no")
	}
	w := httptest.NewRecorder()
	apiTermodomControls(w, httptest.NewRequest("POST", "/api/termodom/xiaomi", strings.NewReader(`{"src":"synthetic-control","id":"camera-on","value":false}`)))
	if w.Code != 409 || writes != 0 {
		t.Fatal("failed read permitted write")
	}
}
func TestTermodomControlsRejectForeignDeviceAndDangerousRequests(t *testing.T) {
	setupControlTest(t)
	writes := 0
	controlCloudRequest = func(_, _, path, _ string) ([]byte, error) {
		if path == "/v2/home/device_list_page" {
			return []byte(`{"list":[{"did":"someone-else","model":"test.camera.fixture"}]}`), nil
		}
		writes++
		return nil, nil
	}
	w := httptest.NewRecorder()
	apiTermodomControls(w, httptest.NewRequest("GET", "/api/termodom/xiaomi?src=synthetic-control", nil))
	if w.Code != 403 || writes != 0 {
		t.Fatal("foreign camera reached settings")
	}
	controlCloudRequest = func(_, _, path, _ string) ([]byte, error) {
		if path == "/v2/home/device_list_page" {
			return ownershipFixture(), nil
		}
		writes++
		return nil, nil
	}
	for _, body := range []string{`{"src":"synthetic-control","id":"format","value":true}`, `{"src":"synthetic-control","id":"camera-on","value":0}`, `{"src":"synthetic-control","id":"night-shot","value":0}`, `{"src":"synthetic-control","id":"image-rollover","value":90}`, `{"src":"synthetic-control","id":"sd-status","value":0}`} {
		w = httptest.NewRecorder()
		apiTermodomControls(w, httptest.NewRequest("POST", "/api/termodom/xiaomi", strings.NewReader(body)))
		if w.Code != 400 {
			t.Fatalf("unsafe request accepted: %d", w.Code)
		}
	}
	if writes != 0 {
		t.Fatal("unsafe request made provider call")
	}
}
func TestTermodomControlsReadbackMismatchIsNotSuccess(t *testing.T) {
	setupControlTest(t)
	controlCloudRequest = func(_, _, path, _ string) ([]byte, error) {
		switch path {
		case "/v2/home/device_list_page":
			return ownershipFixture(), nil
		case "/miotspec/prop/get":
			return []byte(`[{"did":"synthetic-device","siid":47,"piid":77,"code":0,"value":true}]`), nil
		case "/miotspec/prop/set":
			return []byte(`[{"did":"synthetic-device","siid":47,"piid":77,"code":0}]`), nil
		}
		return nil, nil
	}
	w := httptest.NewRecorder()
	apiTermodomControls(w, httptest.NewRequest("POST", "/api/termodom/xiaomi", strings.NewReader(`{"src":"synthetic-control","id":"camera-on","value":false}`)))
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"confirmed":false`) {
		t.Fatal("unconfirmed value claimed success")
	}
}
func TestTermodomControlsMalformedPropertyRepliesStayUnknown(t *testing.T) {
	setupControlTest(t)
	controls, _ := parseControlProfile([]byte(syntheticCameraSchema))
	target := controlTarget{user: "a", region: "de", did: "d"}
	for _, reply := range []string{`[]`, `[{"did":"d","siid":47,"piid":77,"value":false}]`, `[{"did":"different","siid":47,"piid":77,"code":0,"value":false}]`, `[{"did":"d","siid":47,"piid":77,"code":0,"value":0}]`, `{}`} {
		controlCloudRequest = func(string, string, string, string) ([]byte, error) { return []byte(reply), nil }
		result := readCameraControls(target, append([]cameraControl(nil), controls...))
		if result[0].Known || result[0].Writable || result[0].Value != nil {
			t.Fatal("malformed read was shown as actual value")
		}
	}
}
func TestTermodomPTZModelGateAndThrottle(t *testing.T) {
	for _, model := range []string{"chuangmi.camera.021a04", "chuangmi.camera.029a02", "isa.camera.df3", "unknown.camera.model"} {
		if supportedControlPTZ(model) || controlPTZOperation(model, "left") != 0 {
			t.Fatal("PTZ guessed for another model")
		}
	}
	for i, direction := range []string{"left", "right", "up", "down"} {
		if controlPTZOperation("chuangmi.camera.026c02", direction) != byte(i+1) {
			t.Fatal("PTZ mapping wrong")
		}
	}
	if controlPTZOperation("chuangmi.camera.026c05", "continuous") != 0 {
		t.Fatal("unbounded movement enabled")
	}
	oldNow, oldLast := controlPTZNow, controlPTZLast
	t.Cleanup(func() { controlPTZNow = oldNow; controlPTZLast = oldLast })
	now := time.Unix(100, 0)
	controlPTZNow = func() time.Time { return now }
	controlPTZLast = map[string]time.Time{}
	if !allowControlPTZ("d") || allowControlPTZ("d") {
		t.Fatal("step throttle failed")
	}
	now = now.Add(600 * time.Millisecond)
	if !allowControlPTZ("d") {
		t.Fatal("bounded next step rejected")
	}
}
