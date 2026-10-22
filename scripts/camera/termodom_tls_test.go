package main

import (
	"crypto/x509"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestTermodomNativeTLSRequiresTrustedRootAndHostname(t *testing.T) {
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, `{"type":"`+termodomTLSModel+`","services":[{}]}`)
	}))
	server.Config.ErrorLog = log.New(io.Discard, "", 0)
	server.StartTLS()
	defer server.Close()
	untrusted := newTermodomTLSClient(x509.NewCertPool())
	defer untrusted.CloseIdleConnections()
	if verifyTermodomTLSSchema(untrusted, server.URL) == nil {
		t.Fatal("untrusted TLS certificate was accepted")
	}
	roots := x509.NewCertPool()
	roots.AddCert(server.Certificate())
	trusted := newTermodomTLSClient(roots)
	defer trusted.CloseIdleConnections()
	if err := verifyTermodomTLSSchema(trusted, server.URL); err != nil {
		t.Fatal("trusted HTTPS fixture rejected", err)
	}
	if trusted.Transport.(*http.Transport).TLSClientConfig.InsecureSkipVerify {
		t.Fatal("TLS verification disabled")
	}
	badHost := strings.Replace(server.URL, "127.0.0.1", "localhost", 1)
	if verifyTermodomTLSSchema(trusted, badHost) == nil {
		t.Fatal("wrong TLS hostname was accepted")
	}
}
func TestTermodomNativeTLSDeniesRedirectAndInvalidBody(t *testing.T) {
	for _, mode := range []string{"redirect", "oversized", "wrong-model", "empty"} {
		t.Run(mode, func(t *testing.T) {
			redirected := false
			server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/redirected" {
					redirected = true
				}
				switch mode {
				case "redirect":
					http.Redirect(w, r, "/redirected", 302)
				case "oversized":
					_, _ = io.WriteString(w, strings.Repeat("x", (512<<10)+1))
				case "wrong-model":
					_, _ = io.WriteString(w, `{"type":"another model","services":[{}]}`)
				}
			}))
			defer server.Close()
			roots := x509.NewCertPool()
			roots.AddCert(server.Certificate())
			client := newTermodomTLSClient(roots)
			defer client.CloseIdleConnections()
			if verifyTermodomTLSSchema(client, server.URL) == nil {
				t.Fatal("invalid HTTPS result accepted")
			}
			if redirected {
				t.Fatal("native TLS check followed a redirect")
			}
		})
	}
}
