package xiaomi

import (
	"net/http"
	"time"

	"github.com/AlexxIT/go2rtc/internal/api"
	"github.com/AlexxIT/go2rtc/internal/streams"
)

// These exact step mappings have primary-author reports. They are deliberately
// not generalized to other Mi 360 cameras or to legacy Dafang motor commands.
func supportedControlPTZ(model string) bool {
	return model == "chuangmi.camera.026c02" || model == "chuangmi.camera.026c05"
}

var controlPTZLast = map[string]time.Time{}
var controlPTZNow = time.Now

type controlMotor interface{ TermodomMotorStep(byte) (bool, error) }

func controlPTZOperation(model, direction string) byte {
	if !supportedControlPTZ(model) {
		return 0
	}
	switch direction {
	case "left":
		return 1
	case "right":
		return 2
	case "up":
		return 3
	case "down":
		return 4
	}
	return 0
}
func allowControlPTZ(key string) bool {
	now := controlPTZNow()
	if previous, ok := controlPTZLast[key]; ok && now.Sub(previous) < 600*time.Millisecond {
		return false
	}
	controlPTZLast[key] = now
	return true
}
func handleControlPTZ(w http.ResponseWriter, r *http.Request, src string, target controlTarget, direction string) {
	operation := controlPTZOperation(target.model, direction)
	if operation == 0 {
		http.Error(w, "unsupported camera direction", 400)
		return
	}
	stream := streams.Get(src)
	if stream == nil {
		http.Error(w, "camera stream unavailable", 409)
		return
	}
	for _, producer := range stream.TermodomConnections() {
		if motor, ok := producer.(controlMotor); ok {
			if !allowControlPTZ(target.user + "/" + target.did) {
				http.Error(w, "camera movement too frequent", 429)
				return
			}
			acknowledged, err := motor.TermodomMotorStep(operation)
			if err != nil {
				http.Error(w, "camera movement not sent", 502)
				return
			}
			api.ResponseJSON(w, map[string]any{"sent": true, "acknowledged": acknowledged})
			return
		}
	}
	http.Error(w, "open live camera before moving", 409)
}
