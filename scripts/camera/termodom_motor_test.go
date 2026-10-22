package miss

import (
	"encoding/binary"
	"io"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/AlexxIT/go2rtc/pkg/xiaomi/crypto"
)

func TestTermodomMotorOnlyKnownModelsSingleStepPayload(t *testing.T) {
	for _, model := range []string{"chuangmi.camera.026c02", "chuangmi.camera.026c05"} {
		c := &Client{model: model}
		for operation := byte(1); operation <= 4; operation++ {
			payload, err := c.termodomMotorPayload(operation)
			if err != nil || binary.BigEndian.Uint32(payload) != cmdMotorReq || !strings.Contains(string(payload[4:]), `"operation":`) {
				t.Fatal("bounded motor request invalid")
			}
		}
		for _, operation := range []byte{0, 5, 255} {
			if _, err := c.termodomMotorPayload(operation); err == nil {
				t.Fatal("unbounded operation accepted")
			}
		}
	}
	for _, model := range []string{ModelDafang, "chuangmi.camera.029a02", "chuangmi.camera.021a04"} {
		if _, err := (&Client{model: model}).termodomMotorPayload(1); err == nil {
			t.Fatal("unknown motor profile accepted")
		}
	}
}

type syntheticMotorConn struct {
	command uint32
	data    []byte
	done    bool
}

func (c *syntheticMotorConn) ReadCommand() (uint32, []byte, error) {
	if c.done {
		return 0, nil, io.EOF
	}
	c.done = true
	return c.command, c.data, nil
}
func (*syntheticMotorConn) Protocol() string                    { return "test" }
func (*syntheticMotorConn) Version() string                     { return "test" }
func (*syntheticMotorConn) WriteCommand(uint32, []byte) error   { return nil }
func (*syntheticMotorConn) ReadPacket() ([]byte, []byte, error) { return nil, nil, io.EOF }
func (*syntheticMotorConn) WritePacket([]byte, []byte) error    { return nil }
func (*syntheticMotorConn) RemoteAddr() net.Addr                { return nil }
func (*syntheticMotorConn) SetDeadline(time.Time) error         { return nil }
func (*syntheticMotorConn) Close() error                        { return nil }
func TestTermodomMotorAcknowledgementPlainAndEncrypted(t *testing.T) {
	key := make([]byte, 32)
	for _, body := range []string{`{"result":"success"}`, `{"code":0}`, `{"ret":0}`} {
		for _, encrypted := range []bool{false, true} {
			command := uint32(cmdMotorRes)
			data := []byte(body)
			if encrypted {
				var err error
				data, err = crypto.Encode(append(binary.BigEndian.AppendUint32(nil, cmdMotorRes), data...), key)
				if err != nil {
					t.Fatal(err)
				}
				command = cmdEncoded
			}
			c := &Client{Conn: &syntheticMotorConn{command: command, data: data}, key: key, motorReplies: make(chan bool, 4)}
			c.readTermodomReplies()
			select {
			case ok := <-c.motorReplies:
				if !ok {
					t.Fatal("actual acknowledgement not recognized")
				}
			default:
				t.Fatal("no acknowledgement")
			}
		}
	}
	c := &Client{Conn: &syntheticMotorConn{command: cmdEncoded, data: []byte{1}}, key: key, motorReplies: make(chan bool, 4)}
	c.readTermodomReplies() // malformed encrypted camera input must not panic
}
