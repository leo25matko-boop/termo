package miss

// Narrow adaptation of the MIT homebridge-xiaomi-go2rtc command transport at
// 66da2a01eb3a31d100c0fea39901b1683aa4c2e8. No generic command API is exported.
import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"math/bits"
	"time"

	"github.com/AlexxIT/go2rtc/pkg/xiaomi/crypto"
)

func (c *Client) readTermodomReplies() {
	for {
		command, data, err := c.Conn.ReadCommand()
		if err != nil {
			return
		}
		if command == cmdEncoded || bits.ReverseBytes32(command) == cmdEncoded {
			if len(data) < 8 {
				continue
			}
			data, err = crypto.Decode(data, c.key)
			if err != nil || len(data) < 4 {
				continue
			}
			command = binary.BigEndian.Uint32(data[:4])
			data = data[4:]
		}
		if command != cmdMotorRes && bits.ReverseBytes32(command) != cmdMotorRes {
			continue
		}
		var reply struct {
			Result string `json:"result"`
			Code   *int   `json:"code"`
			Ret    *int   `json:"ret"`
		}
		ok := json.Unmarshal(data, &reply) == nil && (reply.Result == "success" || (reply.Code != nil && *reply.Code == 0) || (reply.Ret != nil && *reply.Ret == 0))
		select {
		case c.motorReplies <- ok:
		default:
		}
	}
}
func (c *Client) termodomMotorPayload(operation byte) ([]byte, error) {
	if (c.model != "chuangmi.camera.026c02" && c.model != "chuangmi.camera.026c05") || operation < 1 || operation > 4 {
		return nil, errors.New("unsupported camera step")
	}
	body, err := json.Marshal(struct {
		Operation byte `json:"operation"`
	}{operation})
	if err != nil {
		return nil, err
	}
	payload := binary.BigEndian.AppendUint32(nil, cmdMotorReq)
	return append(payload, body...), nil
}
func (c *Client) TermodomMotorStep(operation byte) (bool, error) {
	payload, err := c.termodomMotorPayload(operation)
	if err != nil {
		return false, err
	}
	c.motorMutex.Lock()
	defer c.motorMutex.Unlock()
	for len(c.motorReplies) > 0 {
		<-c.motorReplies
	}
	if err = c.WriteCommand(payload); err != nil {
		return false, err
	}
	select {
	case acknowledged := <-c.motorReplies:
		return acknowledged, nil
	case <-time.After(2 * time.Second):
		return false, nil
	}
}
func (p *Producer) TermodomMotorStep(operation byte) (bool, error) {
	return p.client.TermodomMotorStep(operation)
}
