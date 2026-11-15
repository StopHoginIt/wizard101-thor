package main

// Login against the KingsIsle login server and fetch a one-time CK2 session
// token for WizardGraphicalClient.exe. Protocol and crypto come from
// cedws/w101-client-go and cedws/w101-proto-go (Go module dependencies);
// the flow follows cedws/umbra-launcher.

import (
	"context"
	"errors"
	"fmt"
	"time"

	crypto "github.com/cedws/w101-client-go/login"
	"github.com/cedws/w101-proto-go/pkg/login"
	"github.com/cedws/w101-client-go/proto"
)

const loginServerRetries = 10

type authError struct {
	Code   int32
	Reason string
}

func (e *authError) Error() string {
	if e.Reason != "" {
		return fmt.Sprintf("KingsIsle refused the login (code %d: %s)", e.Code, e.Reason)
	}
	return fmt.Sprintf("KingsIsle refused the login (code %d)", e.Code)
}

type loginHandler struct {
	login.Service
	ch chan login.UserAuthenRsp
}

func (l loginHandler) UserAuthenRsp(m login.UserAuthenRsp) { l.ch <- m }

func requestCK2(ctx context.Context, addr, username, password string) (uint64, string, error) {
	ch := make(chan login.UserAuthenRsp, 1)
	r := proto.NewMessageRouter()
	login.RegisterService(&r, &loginHandler{ch: ch})

	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()

	var (
		c   *proto.Client
		err error
	)
	for i := 0; i < loginServerRetries; i++ {
		c, err = proto.Dial(ctx, addr, &r)
		if err == nil {
			break
		}
		logf("login server dial attempt %d failed: %v", i+1, err)
		select {
		case <-ctx.Done():
			return 0, "", fmt.Errorf("could not reach the login server: %w", err)
		case <-time.After(500 * time.Millisecond):
		}
	}
	if err != nil {
		return 0, "", fmt.Errorf("could not reach the login server: %w", err)
	}
	defer c.Close()

	sid := c.SessionID()
	secs := c.SessionTimeSecs()
	millis := c.SessionTimeMillis()

	ck1 := crypto.GenerateCK1(password, sid, secs, millis)
	token := crypto.AuthenToken(username, ck1, sid)
	rec1 := crypto.EncryptRec1(token, sid, secs, millis)

	if err := login.NewClient(c).UserAuthenV3(&login.UserAuthenV3{Rec1: string(rec1), Locale: "English"}); err != nil {
		return 0, "", err
	}

	select {
	case rsp := <-ch:
		if rsp.Error != 0 {
			return 0, "", &authError{Code: int32(rsp.Error), Reason: rsp.Reason}
		}
		ck2 := crypto.DecryptRec1([]byte(rsp.Rec1), sid, secs, millis)
		return rsp.UserID, string(ck2), nil
	case <-ctx.Done():
		return 0, "", errors.New("timed out waiting for the login server to answer")
	}
}
