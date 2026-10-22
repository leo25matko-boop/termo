package main

// A native diagnostic that validates the same system trust configuration used
// by the transport. It runs before app.Init, opens no listener, and contacts one
// fixed public model-schema URL. No account, device, or credential is involved.
import (
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"runtime"
	"time"
)

const termodomTLSFlag = "-termodom-verify-tls"
const termodomTLSModel = "urn:miot-spec-v2:device:camera:0000A01C:chuangmi-029a02:2"
const termodomTLSURL = "https://miot-spec.org/miot-spec-v2/instance?type=urn%3Amiot-spec-v2%3Adevice%3Acamera%3A0000A01C%3Achuangmi-029a02%3A2"

func newTermodomTLSClient(roots *x509.CertPool) *http.Client {
	return &http.Client{
		Timeout:       15 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		Transport: &http.Transport{
			Proxy:                 nil,
			DialContext:           (&net.Dialer{Timeout: 8 * time.Second, KeepAlive: 15 * time.Second}).DialContext,
			TLSClientConfig:       &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: roots},
			TLSHandshakeTimeout:   8 * time.Second,
			ResponseHeaderTimeout: 10 * time.Second,
		},
	}
}
func verifyTermodomTLSSchema(client *http.Client, endpoint string) error {
	response, err := client.Get(endpoint)
	if err != nil {
		return errors.New("system HTTPS verification failed")
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return errors.New("public HTTPS schema unavailable")
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, (512<<10)+1))
	if err != nil || len(body) > 512<<10 {
		return errors.New("public HTTPS response invalid")
	}
	var schema struct {
		Type     string            `json:"type"`
		Services []json.RawMessage `json:"services"`
	}
	if json.Unmarshal(body, &schema) != nil || schema.Type != termodomTLSModel || len(schema.Services) == 0 {
		return errors.New("public HTTPS schema invalid")
	}
	return nil
}
func termodomTLSCLI() bool {
	if len(os.Args) != 2 || os.Args[1] != termodomTLSFlag {
		return false
	}
	roots, err := x509.SystemCertPool()
	rootCount := 0
	if roots != nil {
		rootCount = len(roots.Subjects())
	}
	// Android must have the PEM export from its original platform trust store.
	// Windows may use its native system verifier without enumerating OS roots.
	if err != nil || roots == nil || (runtime.GOOS == "android" && rootCount == 0) {
		fmt.Fprintln(os.Stderr, "TERMODOM_TLS_ERROR: system trust unavailable")
		os.Exit(1)
	}
	client := newTermodomTLSClient(roots)
	defer client.CloseIdleConnections()
	if verifyTermodomTLSSchema(client, termodomTLSURL) != nil {
		fmt.Fprintln(os.Stderr, "TERMODOM_TLS_ERROR: verified HTTPS unavailable")
		os.Exit(1)
	}
	fmt.Printf("TERMODOM_TLS_OK: systemRoots=%d; verifiedHTTPS=miot-spec.org\n", rootCount)
	return true
}
