package main

// Update check and patching, adapted from cedws/umbra-launcher.
//
// Every launch asks the patch server for the latest file-list version (one
// small TCP message). Only when that version differs from the last one this
// launcher saw (or when the user asks for "Check game files") does it walk the
// file list, verify the installed files and download the ones that changed.

import (
	"bufio"
	"context"
	"encoding/binary"
	"fmt"
	"hash/crc32"
	"io"
	"math/bits"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"slices"
	"sync"
	"sync/atomic"
	"time"

	"github.com/cedws/w101-client-go/dml"
	"github.com/cedws/w101-proto-go/pkg/patch"
	"github.com/cedws/w101-client-go/proto"
)

const (
	fileTypeDynamicWAD = 5
)

var metaTables = []string{"_TableList", "About", "PatchClient"}

var crcTable = func() *crc32.Table {
	return crc32.MakeTable(bits.Reverse32(0x4C11DB7))
}()

// KingsIsle's CRC: reflected CRC-32 table on the normal polynomial, init/final 0xFFFFFFFF.
type kiHasher struct{ sum uint32 }

func newKIHasher() *kiHasher                    { return &kiHasher{sum: 0xFFFFFFFF} }
func (h *kiHasher) Write(b []byte) (int, error) { h.sum = crc32.Update(h.sum, crcTable, b); return len(b), nil }
func (h *kiHasher) Sum32() uint32               { return h.sum ^ 0xFFFFFFFF }

type patchHandler struct {
	patch.Service
	ch chan patch.LatestFileListV2
}

func (p patchHandler) LatestFileListV2(m patch.LatestFileListV2) { p.ch <- m }

func latestFileList(ctx context.Context, addr string) (*patch.LatestFileListV2, error) {
	ch := make(chan patch.LatestFileListV2, 1)
	r := proto.NewMessageRouter()
	patch.RegisterService(&r, &patchHandler{ch: ch})

	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()

	c, err := proto.Dial(ctx, addr, &r)
	if err != nil {
		return nil, err
	}
	defer c.Close()

	if err := patch.NewClient(c).LatestFileListV2(&patch.LatestFileListV2{}); err != nil {
		return nil, err
	}
	select {
	case fl := <-ch:
		return &fl, nil
	case <-ctx.Done():
		return nil, fmt.Errorf("timed out waiting for the patch server")
	}
}

type patcher struct {
	root     *os.Root
	http     *http.Client
	progress func(done, total int64, note string)

	done, total atomic.Int64
	downloaded  atomic.Int64
}

func httpGet(ctx context.Context, c *http.Client, u string) (io.ReadCloser, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "KingsIsle Patcher")
	resp, err := c.Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		resp.Body.Close()
		return nil, fmt.Errorf("HTTP %d for %s", resp.StatusCode, u)
	}
	return resp.Body, nil
}

func readPackages(root *os.Root) []string {
	f, err := root.Open("LocalPackagesList.txt")
	if err != nil {
		return nil
	}
	defer f.Close()
	var pkgs []string
	s := bufio.NewScanner(f)
	for s.Scan() {
		if t := s.Text(); t != "" {
			pkgs = append(pkgs, t)
		}
	}
	return pkgs
}

// cachedCRCs reads PatchInfo/CRC_<table>.dat records: nameCRC, size, crc, 0, timestamp.
func cachedCRCs(root *os.Root, table string) map[uint32][2]uint32 {
	out := map[uint32][2]uint32{}
	b, err := readAllRoot(root, fmt.Sprintf("PatchInfo/CRC_%v.dat", table))
	if err != nil {
		return out
	}
	for i := 0; i+24 <= len(b); i += 24 {
		name := binary.LittleEndian.Uint32(b[i:])
		size := binary.LittleEndian.Uint32(b[i+4:])
		crc := binary.LittleEndian.Uint32(b[i+8:])
		out[name] = [2]uint32{size, crc}
	}
	return out
}

func readAllRoot(root *os.Root, name string) ([]byte, error) {
	f, err := root.Open(name)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return io.ReadAll(f)
}

func nameCRC(s string) uint32 {
	h := newKIHasher()
	h.Write([]byte(s))
	return h.Sum32()
}

// run verifies and repairs the base files and every package listed in
// LocalPackagesList.txt. trustCache lets files whose size and CRC match the
// previous run's CRC cache skip re-hashing.
func (p *patcher) run(ctx context.Context, fl *patch.LatestFileListV2, trustCache bool) error {
	body, err := httpGet(ctx, p.http, fl.ListFileURL)
	if err != nil {
		return fmt.Errorf("download file list: %w", err)
	}
	defer body.Close()

	p.root.Mkdir("PatchInfo", 0o755)
	cache, err := p.root.Create("PatchInfo/LatestFileList.bin")
	if err != nil {
		return err
	}
	tables, err := dml.DecodeTable(io.TeeReader(body, cache))
	cache.Close()
	if err != nil {
		return fmt.Errorf("decode file list: %w", err)
	}

	pkgs := readPackages(p.root)
	want := func(name string) bool {
		if slices.Contains(metaTables, name) {
			return false
		}
		return name == "Base" || slices.Contains(pkgs, name)
	}

	for _, t := range *tables {
		if want(t.Name) {
			p.total.Add(int64(len(t.Records)))
		}
	}
	logf("patch: list %s, %d records to check, packages=%d", fl.ListFileURL, p.total.Load(), len(pkgs))

	for _, t := range *tables {
		if !want(t.Name) {
			continue
		}
		var prev map[uint32][2]uint32
		if trustCache {
			prev = cachedCRCs(p.root, t.Name)
		}
		var mu sync.Mutex
		var crcRecords []byte
		sem := make(chan struct{}, 4)
		var wg sync.WaitGroup
		var firstErr atomic.Value

		for _, rec := range t.Records {
			if ctx.Err() != nil {
				break
			}
			rec := rec
			sem <- struct{}{}
			wg.Add(1)
			go func() {
				defer func() { <-sem; wg.Done() }()
				line, err := p.processRecord(ctx, fl.URLPrefix, rec, prev)
				if err != nil {
					firstErr.CompareAndSwap(nil, err)
					return
				}
				if line != nil {
					mu.Lock()
					crcRecords = append(crcRecords, line...)
					mu.Unlock()
				}
				n := p.done.Add(1)
				if p.progress != nil && n%25 == 0 {
					p.progress(n, p.total.Load(), "")
				}
			}()
		}
		wg.Wait()
		if e, ok := firstErr.Load().(error); ok && e != nil {
			return e
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if f, err := p.root.Create(fmt.Sprintf("PatchInfo/CRC_%v.dat", t.Name)); err == nil {
			f.Write(crcRecords)
			f.Close()
		}
	}
	logf("patch: done, %d files downloaded", p.downloaded.Load())
	return nil
}

func (p *patcher) processRecord(ctx context.Context, prefix string, rec dml.Record, prev map[uint32][2]uint32) ([]byte, error) {
	src, _ := rec["SrcFileName"].(string)
	tgt, _ := rec["TarFileName"].(string)
	crc, _ := rec["CRC"].(uint32)
	size, _ := rec["Size"].(uint32)
	ftype, _ := rec["FileType"].(uint32)
	if src == "" {
		return nil, nil
	}
	if tgt == "" {
		tgt = src
	}
	if ftype == fileTypeDynamicWAD {
		// Streamed in segments at runtime; their CRC never matches. Skip like umbra does.
		return nil, nil
	}
	tgt = path.Clean(tgt)
	nc := nameCRC(src)

	ok := false
	if c, hit := prev[nc]; hit && c[0] == size && c[1] == crc {
		if st, err := p.root.Stat(tgt); err == nil && !st.IsDir() && st.Size() == int64(size) {
			ok = true
		}
	}
	if !ok {
		var err error
		ok, err = p.verify(tgt, size, crc)
		if err != nil {
			return nil, err
		}
	}
	if !ok {
		u, err := url.JoinPath(prefix, src)
		if err != nil {
			return nil, err
		}
		if p.progress != nil {
			p.progress(p.done.Load(), p.total.Load(), "Downloading "+tgt)
		}
		if err := p.download(ctx, u, tgt, size, crc); err != nil {
			return nil, err
		}
		p.downloaded.Add(1)
	}

	var b []byte
	b = binary.LittleEndian.AppendUint32(b, nc)
	b = binary.LittleEndian.AppendUint32(b, size)
	b = binary.LittleEndian.AppendUint32(b, crc)
	b = binary.LittleEndian.AppendUint32(b, 0)
	b = binary.LittleEndian.AppendUint64(b, uint64(time.Now().UnixMilli()))
	return b, nil
}

func (p *patcher) verify(tgt string, size, crc uint32) (bool, error) {
	st, err := p.root.Stat(tgt)
	if os.IsNotExist(err) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if st.IsDir() || st.Size() != int64(size) {
		return false, nil
	}
	f, err := p.root.Open(tgt)
	if err != nil {
		return false, err
	}
	defer f.Close()
	h := newKIHasher()
	if _, err := io.CopyN(h, bufio.NewReaderSize(f, 1<<20), int64(size)); err != nil {
		return false, err
	}
	return h.Sum32() == crc, nil
}

func (p *patcher) download(ctx context.Context, u, tgt string, size, crc uint32) error {
	if dir := filepath.Dir(tgt); dir != "." {
		if err := mkdirAllRoot(p.root, dir); err != nil {
			return err
		}
	}
	body, err := httpGet(ctx, p.http, u)
	if err != nil {
		return err
	}
	defer body.Close()

	tmp := tgt + ".w101quick.part"
	f, err := p.root.Create(tmp)
	if err != nil {
		return err
	}
	h := newKIHasher()
	_, err = io.CopyN(f, io.TeeReader(body, h), int64(size))
	f.Close()
	if err != nil {
		p.root.Remove(tmp)
		return fmt.Errorf("download %s: %w", tgt, err)
	}
	if h.Sum32() != crc {
		p.root.Remove(tmp)
		return fmt.Errorf("download %s: checksum mismatch", tgt)
	}
	p.root.Remove(tgt)
	// os.Root has no Rename before Go 1.25: rename through the real path.
	base := p.root.Name()
	return os.Rename(filepath.Join(base, filepath.FromSlash(tmp)), filepath.Join(base, filepath.FromSlash(tgt)))
}

func mkdirAllRoot(root *os.Root, dir string) error {
	dir = filepath.Clean(dir)
	var parts []string
	for d := dir; d != "." && d != string(filepath.Separator) && d != "/"; d = filepath.Dir(d) {
		parts = append([]string{d}, parts...)
		if filepath.Dir(d) == d {
			break
		}
	}
	for _, d := range parts {
		if err := root.Mkdir(d, 0o755); err != nil {
			if st, e2 := root.Stat(d); e2 != nil || !st.IsDir() {
				return err
			}
		}
	}
	return nil
}
