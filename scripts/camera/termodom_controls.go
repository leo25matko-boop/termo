package xiaomi

// Independently implemented restricted MIoT adapter. Protocol references:
// homebridge-xiaomi-go2rtc (MIT), commit 66da2a01eb3a31d100c0fea39901b1683aa4c2e8.
// All numeric property IDs/options come from the model's public MIoT schema.
// There is deliberately no arbitrary RPC, action, reset, format, or firmware API.
import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"math"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/AlexxIT/go2rtc/internal/api"
	"github.com/AlexxIT/go2rtc/internal/streams"
)

var controlMutex sync.Mutex
var controlCloudRequest = cloudRequest
var controlFetch = fetchControlSpec
var controlProfiles = map[string]cachedControlProfile{}
var controlModelPattern = regexp.MustCompile(`^[a-z0-9_-]+\.(camera|cateye)\.[a-z0-9_-]+$`)
var controlDidPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)
var controlTimePattern = regexp.MustCompile(`^(?:[01][0-9]|2[0-3]):[0-5][0-9]$`)

type controlOption struct {
	Value any    `json:"value"`
	Label string `json:"label"`
}
type cameraControl struct {
	ID       string          `json:"id"`
	Label    string          `json:"label"`
	Group    string          `json:"group"`
	Format   string          `json:"format"`
	Role     string          `json:"role,omitempty"`
	Readable bool            `json:"readable"`
	Writable bool            `json:"writable"`
	Known    bool            `json:"known"`
	Value    any             `json:"value"`
	Options  []controlOption `json:"options,omitempty"`
	Minimum  *float64        `json:"minimum,omitempty"`
	Maximum  *float64        `json:"maximum,omitempty"`
	Step     *float64        `json:"step,omitempty"`
	Unit     string          `json:"unit,omitempty"`
	siid     int
	piid     int
	canWrite bool
}
type controlRule struct {
	id, label, group string
	readOnly         bool
}

var controlRules = map[string]controlRule{
	"camera-control/on":                            {"camera-on", "Kamera uključena", "Privatnost", false},
	"camera-control/image-rollover":                {"image-rollover", "Okretanje slike", "Slika", false},
	"camera-control/night-shot":                    {"night-shot", "Noćni prikaz", "Slika", false},
	"camera-control/wdr-mode":                      {"wdr-mode", "WDR", "Slika", false},
	"camera-control/glimmer-full-color":            {"glimmer-full-color", "Boja pri slabom svjetlu", "Slika", false},
	"camera-control/recording-mode":                {"recording-mode", "Snimanje na SD karticu", "Pohrana", false},
	"camera-control/motion-tracking":               {"motion-tracking", "Praćenje pokreta", "Detekcija", false},
	"camera-control/time-watermark":                {"time-watermark", "Datum i vrijeme na slici", "Slika", false},
	"indicator-light/on":                           {"indicator-light", "Indikatorska lampica", "Uređaj", false},
	"motion-detection/motion-detection":            {"motion-detection", "Detekcija pokreta", "Detekcija", false},
	"motion-detection/alarm-interval":              {"alarm-interval", "Razmak alarma", "Detekcija", false},
	"motion-detection/detection-sensitivity":       {"detection-sensitivity", "Osjetljivost detekcije", "Detekcija", false},
	"motion-detection/motion-detection-start-time": {"detection-start", "Početak detekcije", "Detekcija", false},
	"motion-detection/motion-detection-end-time":   {"detection-end", "Kraj detekcije", "Detekcija", false},
	"memory-card-management/status":                {"sd-status", "Stanje SD kartice", "Pohrana", true},
	"memory-card-management/storage-total-space":   {"sd-total", "Kapacitet SD kartice", "Pohrana", true},
	"memory-card-management/storage-free-space":    {"sd-free", "Slobodno na SD kartici", "Pohrana", true},
	"memory-card-management/storage-used-space":    {"sd-used", "Zauzeto na SD kartici", "Pohrana", true},
	"speaker/volume":                               {"speaker-volume", "Glasnoća zvučnika", "Zvuk", false},
}

type miotSpec struct {
	Type     string        `json:"type"`
	Services []miotService `json:"services"`
}
type miotService struct {
	IID        int            `json:"iid"`
	Type       string         `json:"type"`
	Properties []miotProperty `json:"properties"`
}
type miotProperty struct {
	IID    int       `json:"iid"`
	Type   string    `json:"type"`
	Format string    `json:"format"`
	Access []string  `json:"access"`
	Range  []float64 `json:"value-range"`
	List   []struct {
		Value       any    `json:"value"`
		Description string `json:"description"`
	} `json:"value-list"`
	Unit string `json:"unit"`
}
type cachedControlProfile struct {
	controls []cameraControl
	fetched  time.Time
}
type controlTarget struct{ user, region, did, model, specType string }

func controlURNName(urn string, kind string) string {
	parts := strings.Split(urn, ":")
	if len(parts) != 7 || parts[0] != "urn" || parts[1] != "miot-spec-v2" || parts[2] != kind {
		return ""
	}
	return parts[3]
}

func parseControlProfile(body []byte) ([]cameraControl, error) {
	var spec miotSpec
	if json.Unmarshal(body, &spec) != nil || controlURNName(spec.Type, "device") != "camera" || len(spec.Services) > 100 {
		return nil, errors.New("invalid public camera schema")
	}
	controls := make([]cameraControl, 0)
	seen := map[string]bool{}
	for _, service := range spec.Services {
		if service.IID <= 0 || service.IID > 1000 {
			continue
		}
		for _, property := range service.Properties {
			rule, ok := controlRules[controlURNName(service.Type, "service")+"/"+controlURNName(property.Type, "property")]
			if !ok || seen[rule.id] || property.IID <= 0 || property.IID > 1000 {
				continue
			}
			read, write := false, false
			for _, access := range property.Access {
				read = read || access == "read"
				write = write || access == "write"
			}
			if !read {
				continue
			}
			c := cameraControl{ID: rule.id, Label: rule.label, Group: rule.group, Format: property.Format, Readable: true, Value: nil, siid: service.IID, piid: property.IID, canWrite: write && !rule.readOnly}
			if rule.id == "camera-on" {
				c.Role = "camera-power"
			}
			switch property.Format {
			case "bool":
			case "string":
				if rule.id != "detection-start" && rule.id != "detection-end" {
					continue
				}
				c.Format = "time"
			case "uint8", "uint16", "uint32", "int8", "int16", "int32", "float":
				c.Format = "number"
				if len(property.List) > 0 && len(property.List) <= 32 {
					valid := true
					for _, item := range property.List {
						value, ok := item.Value.(float64)
						if !ok || math.IsNaN(value) || math.IsInf(value, 0) {
							valid = false
							break
						}
						c.Options = append(c.Options, controlOption{value, controlOptionLabel(item.Description)})
					}
					if !valid {
						continue
					}
				} else if len(property.Range) == 3 && finiteRange(property.Range) {
					c.Minimum = &property.Range[0]
					c.Maximum = &property.Range[1]
					c.Step = &property.Range[2]
				} else {
					continue
				}
			default:
				continue
			}
			// Unit labels are schema-provided; unknown units are omitted, never inferred.
			switch property.Unit {
			case "percentage", "minutes", "seconds", "kilobytes", "megabytes":
				c.Unit = property.Unit
			}
			controls = append(controls, c)
			seen[rule.id] = true
		}
	}
	return controls, nil
}
func finiteRange(r []float64) bool {
	for _, n := range r {
		if math.IsNaN(n) || math.IsInf(n, 0) {
			return false
		}
	}
	return r[0] >= 0 && r[1] >= r[0] && r[1] <= 1e12 && r[2] > 0 && r[2] <= math.Max(1, r[1]-r[0])
}
func controlOptionLabel(s string) string {
	translations := map[string]string{"on": "Uključeno", "off": "Isključeno", "auto": "Automatski", "low": "Niska", "medium": "Srednja", "high": "Visoka", "all record": "Stalno snimanje", "motion record": "Snimanje pokreta", "no record": "Bez snimanja", "normal": "U redu", "not exist": "Nema kartice", "full": "Puna", "broken": "Oštećena", "formating": "Formatiranje", "formatting": "Formatiranje", "eject": "Izbačena", "fixing": "Popravak", "ejecting": "Izbacivanje"}
	if translated, ok := translations[strings.ToLower(strings.TrimSpace(s))]; ok {
		return translated
	}
	s = strings.Map(func(r rune) rune {
		if r < 32 {
			return -1
		}
		return r
	}, s)
	if len(s) > 100 {
		s = s[:100]
	}
	return s
}

// The schema client can contact only the fixed public HTTPS MIoT host. It never
// receives account IDs, camera IDs, tokens, LAN addresses, or user credentials.
func fetchControlSpec(path string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, "GET", "https://miot-spec.org/miot-spec-v2/"+path, nil)
	if err != nil {
		return nil, err
	}
	client := &http.Client{Timeout: 12 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return nil, errors.New("public camera schema unavailable")
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, (16<<20)+1))
	if err != nil || len(body) > 16<<20 {
		return nil, errors.New("invalid public camera schema")
	}
	return body, nil
}

func loadControlProfile(target controlTarget) ([]cameraControl, error) {
	key := target.model + "|" + target.specType
	if cached, ok := controlProfiles[key]; ok && time.Since(cached.fetched) < 6*time.Hour {
		return append([]cameraControl(nil), cached.controls...), nil
	}
	body, err := controlFetch("instances?status=released")
	if err != nil {
		return nil, err
	}
	var index struct {
		Instances []struct {
			Model   string `json:"model"`
			Type    string `json:"type"`
			Version int    `json:"version"`
		} `json:"instances"`
	}
	if json.Unmarshal(body, &index) != nil {
		return nil, errors.New("invalid public camera index")
	}
	urn := ""
	version := -1
	for _, instance := range index.Instances {
		if instance.Model != target.model || controlURNName(instance.Type, "device") != "camera" {
			continue
		}
		if target.specType != "" {
			if instance.Type == target.specType {
				urn = instance.Type
				break
			}
		} else if instance.Version > version {
			urn = instance.Type
			version = instance.Version
		}
	}
	if urn == "" {
		return []cameraControl{}, nil
	}
	body, err = controlFetch("instance?type=" + url.QueryEscape(urn))
	if err != nil {
		return nil, err
	}
	controls, err := parseControlProfile(body)
	if err != nil {
		return nil, err
	}
	var spec miotSpec
	if json.Unmarshal(body, &spec) != nil || spec.Type != urn {
		return nil, errors.New("camera schema type mismatch")
	}
	controlProfiles[key] = cachedControlProfile{append([]cameraControl(nil), controls...), time.Now()}
	return controls, nil
}

func resolveControlTarget(src string) (controlTarget, error) {
	stream := streams.Get(src)
	if stream == nil {
		return controlTarget{}, errors.New("camera not registered")
	}
	for _, raw := range stream.Sources() {
		u, err := url.Parse(raw)
		if err != nil || u.Scheme != "xiaomi" || u.User == nil {
			continue
		}
		region, _ := u.User.Password()
		model := u.Query().Get("model")
		did := u.Query().Get("did")
		if !controlModelPattern.MatchString(model) || !controlDidPattern.MatchString(did) || !validControlRegion(region) {
			continue
		}
		user := u.User.Username()
		cloudsMu.Lock()
		_, hasToken := tokens[user]
		cloudsMu.Unlock()
		if !hasToken {
			return controlTarget{}, errors.New("camera account unavailable")
		}
		// Verify ownership and model against the original account, never trust a
		// URL supplied by an API caller for the device being controlled.
		body, err := controlCloudRequest(user, region, "/v2/home/device_list_page", "{}")
		if err != nil {
			return controlTarget{}, errors.New("camera ownership unavailable")
		}
		var devices struct {
			List []struct {
				Did      string `json:"did"`
				Model    string `json:"model"`
				SpecType string `json:"spec_type"`
			} `json:"list"`
		}
		if json.Unmarshal(body, &devices) != nil {
			return controlTarget{}, errors.New("camera ownership unavailable")
		}
		for _, device := range devices.List {
			if device.Did == did && device.Model == model {
				return controlTarget{user, region, did, model, device.SpecType}, nil
			}
		}
		return controlTarget{}, errors.New("camera ownership not confirmed")
	}
	return controlTarget{}, errors.New("camera source unavailable")
}
func validControlRegion(s string) bool {
	switch s {
	case "cn", "de", "i2", "ru", "sg", "us":
		return true
	}
	return false
}

type miotControlParam struct {
	Did   string `json:"did"`
	Siid  int    `json:"siid"`
	Piid  int    `json:"piid"`
	Value any    `json:"value,omitempty"`
}
type miotControlResult struct {
	Did   string          `json:"did"`
	Siid  int             `json:"siid"`
	Piid  int             `json:"piid"`
	Code  *int            `json:"code"`
	Value json.RawMessage `json:"value"`
}

func readCameraControls(target controlTarget, controls []cameraControl) []cameraControl {
	params := make([]miotControlParam, 0, len(controls))
	for i := range controls {
		controls[i].Known = false
		controls[i].Writable = false
		controls[i].Value = nil
		params = append(params, miotControlParam{Did: target.did, Siid: controls[i].siid, Piid: controls[i].piid})
	}
	if len(params) == 0 {
		return controls
	}
	request, _ := json.Marshal(map[string]any{"params": params})
	body, err := controlCloudRequest(target.user, target.region, "/miotspec/prop/get", string(request))
	if err != nil {
		return controls
	}
	var results []miotControlResult
	if json.Unmarshal(body, &results) != nil {
		return controls
	}
	for i := range controls {
		c := &controls[i]
		for _, result := range results {
			if result.Did != target.did || result.Siid != c.siid || result.Piid != c.piid || result.Code == nil || *result.Code != 0 {
				continue
			}
			var value any
			if json.Unmarshal(result.Value, &value) != nil || !validControlValue(*c, value) {
				continue
			}
			c.Known = true
			c.Value = value
			c.Writable = c.canWrite
			break
		}
	}
	return controls
}
func validControlValue(c cameraControl, value any) bool {
	switch c.Format {
	case "bool":
		_, ok := value.(bool)
		return ok
	case "time":
		s, ok := value.(string)
		return ok && controlTimePattern.MatchString(s)
	case "number":
		number, ok := value.(float64)
		if !ok || math.IsNaN(number) || math.IsInf(number, 0) {
			return false
		}
		if len(c.Options) > 0 {
			for _, option := range c.Options {
				if option.Value == number {
					return true
				}
			}
			return false
		}
		if c.Minimum == nil || c.Maximum == nil || c.Step == nil || number < *c.Minimum || number > *c.Maximum {
			return false
		}
		steps := (number - *c.Minimum) / *c.Step
		return math.Abs(steps-math.Round(steps)) < 1e-7
	}
	return false
}

func apiTermodomControls(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if r.Method != "GET" && r.Method != "POST" {
		http.Error(w, "unsupported camera request", 405)
		return
	}
	// Serializes property operations and schema cache; there are no retries or
	// background writes. Every POST is a direct explicit native UI action.
	controlMutex.Lock()
	defer controlMutex.Unlock()
	src := r.URL.Query().Get("src")
	var request struct {
		Src       string `json:"src"`
		ID        string `json:"id"`
		Value     any    `json:"value"`
		Direction string `json:"direction"`
	}
	if r.Method == "POST" {
		body, err := io.ReadAll(io.LimitReader(r.Body, 4097))
		if err != nil || len(body) > 4096 || json.Unmarshal(body, &request) != nil || request.Src == "" {
			http.Error(w, "invalid camera request", 400)
			return
		}
		src = request.Src
	}
	target, err := resolveControlTarget(src)
	if err != nil {
		http.Error(w, "camera ownership not confirmed", 403)
		return
	}
	if r.Method == "POST" && request.ID == "ptz" {
		handleControlPTZ(w, r, src, target, request.Direction)
		return
	}
	controls, err := loadControlProfile(target)
	if err != nil {
		http.Error(w, "camera settings unavailable", 502)
		return
	}
	if r.Method == "GET" {
		controls = readCameraControls(target, controls)
		api.ResponseJSON(w, map[string]any{"model": target.model, "controls": controls, "ptz": supportedControlPTZ(target.model)})
		return
	}
	index := -1
	for i, c := range controls {
		if c.ID == request.ID {
			index = i
			break
		}
	}
	if index < 0 || !controls[index].canWrite || !validControlValue(controls[index], request.Value) {
		http.Error(w, "unsupported camera setting", 400)
		return
	}
	read := readCameraControls(target, []cameraControl{controls[index]})
	if !read[0].Known || !read[0].Writable {
		http.Error(w, "camera setting unavailable", 409)
		return
	}
	// An explicit value avoids toggle races. Exactly one provider set is sent.
	param := struct {
		Did   string `json:"did"`
		Siid  int    `json:"siid"`
		Piid  int    `json:"piid"`
		Value any    `json:"value"`
	}{target.did, read[0].siid, read[0].piid, request.Value}
	params, _ := json.Marshal(map[string]any{"params": []any{param}})
	body, err := controlCloudRequest(target.user, target.region, "/miotspec/prop/set", string(params))
	var results []miotControlResult
	if err != nil || json.Unmarshal(body, &results) != nil || len(results) != 1 || results[0].Did != target.did || results[0].Siid != param.Siid || results[0].Piid != param.Piid || results[0].Code == nil || *results[0].Code != 0 {
		http.Error(w, "camera setting not accepted", 502)
		return
	}
	refreshed := readCameraControls(target, []cameraControl{controls[index]})[0]
	confirmed := refreshed.Known && refreshed.Value == request.Value
	api.ResponseJSON(w, map[string]any{"control": refreshed, "confirmed": confirmed})
}
