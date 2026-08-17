# Hướng dẫn sử dụng AI-SDLC

Tài liệu này trả lời đúng một câu hỏi: **đăng nhập vào rồi thì làm gì?**

Nó viết cho người quản trị (`admin`) mở portal lần đầu. Mọi bước dưới đây được đối chiếu với code thật, kể cả những chỗ giao diện *không* làm được và bạn buộc phải dùng dòng lệnh.

- [Luật chơi, nói trước](#luật-chơi-nói-trước)
- [Kho tài liệu context](#kho-tài-liệu-context)
- [Tám bước cấu hình](#tám-bước-cấu-hình)
- [Bước 9: dữ liệu vào bằng CLI](#bước-9-dữ-liệu-vào-bằng-cli)
- [Sau khi có dữ liệu](#sau-khi-có-dữ-liệu)
- [Những chỗ dễ mắc](#những-chỗ-dễ-mắc)

## Luật chơi, nói trước

Ba điều nếu không biết thì sẽ thấy hệ thống "rối":

1. **Có thứ tự bắt buộc.** Không tạo tổ chức thì không tạo được dự án; không ghim Spec Kit thì xác thực không chạy. Trang tổng quan (`/app`) hiện checklist 10 bước kèm trạng thái thật, bước nào chưa xong bấm vào là tới đúng chỗ.
2. **Mọi màn hình phụ thuộc vào phạm vi đang chọn.** Thanh chọn `ORG` / `PROJECT` ở đầu trang quyết định bạn thấy gì. Chưa chọn thì phần lớn màn hình trống — đó là đúng, không phải lỗi.
3. **Portal chủ yếu để đọc và quyết định, không phải để nhập liệu.** Bằng chứng xác thực vào hệ thống bằng CLI hoặc webhook SCM. Không có nút "chạy xác thực" nào trên giao diện, và sẽ không có: bằng chứng phải sinh ra từ một lần chạy thật, không phải gõ tay.

## Kho tài liệu context

Đây là chỗ để tài liệu dự án — tài liệu phân tích, quy trình, mô tả nghiệp vụ — để AI đọc được và **trích dẫn được đúng mục**.

Mở **Kho tài liệu** (`/app/knowledge`). Ba cột:

| Cột | Nội dung |
|---|---|
| Trái | Danh sách không gian (space) và cây trang. Trang con thụt vào theo cấp. |
| Giữa | Nội dung trang: tiêu đề, phiên bản, nhãn, lý do sửa, nội dung, và các hiện vật được trích dẫn. |
| Phải | Lịch sử phiên bản. Mỗi lần sửa là một phiên bản mới; bản cũ vẫn đọc được. |

Ô **TÌM** ở trên tìm theo từ khoá, **có bỏ dấu**: gõ `tiep nhan` vẫn ra `tiếp nhận`. Kết quả trả về từng *đoạn*, kèm đường dẫn tiêu đề để biết đoạn đó nằm ở mục nào.

Cần nói thẳng: **đây là tìm theo từ khoá, không phải tìm theo ngữ nghĩa.** Bản triển khai này không có pgvector nên không có embedding. Câu hỏi diễn đạt khác tài liệu sẽ không khớp. Không có kết quả nghĩa là *không từ nào trùng*, chứ không phải *tài liệu không đề cập*. Câu này cũng hiện ngay trên màn hình để không ai hiểu sai.

### Nạp tài liệu từ file Excel

Hai lệnh, cố tình tách rời:

```bash
python3 scripts/workbook-to-pages.py <file.xlsx> --space-key DOCS \
  --parent-slug workbook-index --out pages.json
```

Lệnh này **không nối mạng**. Nó đọc file và ghi ra `pages.json` để bạn đọc trước khi có gì được gửi đi — vì tài liệu loại này thường là tài liệu mật, và gộp hai bước vào một lệnh sẽ mất đúng cơ hội kiểm tra đó. Thêm `--preview` nếu chỉ muốn xem cấu trúc.

```bash
bash scripts/import-pages.sh --payload pages.json --org <organization-uuid> \
  --note "lý do lần nạp này"
```

Chạy lại lệnh thứ hai là cách để **cập nhật**: trang đã có sẽ được tạo phiên bản mới kèm lý do, không nhân bản, không lỗi. Trang không thay đổi thì báo `same` và không ghi gì.

Mỗi sheet thành một trang, **mỗi dòng thành một mục con** riêng. Lý do: nếu để nguyên dạng bảng Markdown thì bảng không có dòng trống, hệ thống sẽ cắt bảng ở giữa và mọi đoạn sau đoạn đầu mất luôn tên cột — AI đọc được giá trị mà không biết cột đó là gì. Đổi lại là dài dòng: tên cột lặp ở mọi dòng. Chính chỗ lặp đó làm một dòng tự nó đủ nghĩa.

Sheet nào không chuyển được (không có dòng nào có từ 2 ô trở lên nên không xác định được dòng tiêu đề) sẽ được **báo ra stderr**, không bị bỏ im lặng.

## Tám bước cấu hình

| # | Việc | Màn hình | Cần biết |
|---|---|---|---|
| 1 | Tạo tổ chức | Projects | Form tạo tổ chức nằm trong trang Projects |
| 2 | Chọn phạm vi | thanh đầu trang | Bấm **Apply scope** mới có hiệu lực |
| 3 | Tạo dự án | Projects | slug chỉ gồm `a-z0-9-` |
| 4 | Mời thành viên | Projects | `Subject` là **Keycloak subject** (UUID), không phải email. Lấy trong Keycloak admin console |
| 5 | Đăng ký Spec Kit | Spec kit registry | Phải dán manifest JSON; `{}` là hợp lệ để bắt đầu. Layer: `CORE`, `EXTENSION`, `PRESET`, `OVERRIDE` |
| 6 | Ghim kit vào dự án | Spec kit registry | Cần chọn project trước. `precedence` 0–10000 |
| 7 | Constitution | Policy & constitution | Tạo xong **phải bấm Activate** mới có hiệu lực |
| 8 | Policy | Policy & constitution | Cũng phải Activate riêng |

Quyền: bước 1, 5, 7, 8 cần vai trò `admin`. `developer` gửi được bằng chứng từ CLI. `reviewer` quyết định phê duyệt. Vai trò realm là chưa đủ — máy chủ còn kiểm tra **thành viên dự án**, nên một `developer` có quyền tổ chức vẫn không đọc được dự án ngoài phạm vi.

## Bước 9: dữ liệu vào bằng CLI

API quản trị **không mở ra host** — đó là chủ ý, và `scripts/end-to-end-acceptance.sh` bước 10 sẽ **fail nếu `localhost:8081` trả lời**. Nên chạy CLI trong mạng của compose:

```bash
docker run --rm --network ai-sdlc_platform -v "$PWD:/w" -w /w/cli golang:1.24 \
  go run ./cmd/aisdlc init --project <project-uuid> --api-url http://management-server:8081 \
  --spec-dir ../my-project/spec-kit --kit-version core@1.0.0 --model provider/model@revision
```

Rồi `validate` và `sync`. Xác thực **không gọi mô hình**; bản ghim mô hình chỉ được lưu làm xuất xứ. Chi tiết trong [operations.md](operations.md).

Sau khi `sync` xong, dữ liệu mới xuất hiện ở Validations, Traceability, Quality.

## Sau khi có dữ liệu

- **Validations** — xem lần chạy, phân loại từng phát hiện. `FALSE_POSITIVE` và `ACCEPTED_RISK` **bắt buộc có lý do**, để việc không sửa cũng phải nói rõ.
- **Evidence repository** — tải hiện vật lên, khoá lưu trữ (`GOVERNANCE` hoặc `COMPLIANCE`). Máy chủ tự tính SHA-256; gửi kèm digest thì nó đối chiếu.
- **Review queue** — tạo mục rà soát và quyết định `APPROVED`/`REJECTED`.
- **Audit ledger** — chuỗi băm chỉ-ghi-thêm; có nút kiểm tra tính toàn vẹn.

## Những chỗ dễ mắc

| Hiện tượng | Nguyên nhân |
|---|---|
| Màn hình nào cũng trống | Chưa chọn `ORG`/`PROJECT` rồi bấm **Apply scope** |
| Đã tạo policy/constitution mà không thấy tác dụng | Chưa bấm **Activate** |
| Xác thực báo thiếu kit | Chưa ghim kit vào dự án; hệ thống cố ý không đoán bản mặc định |
| Chờ mãi không thấy validation run | Không có nút nào trên UI tạo được; phải chạy CLI |
| Mời thành viên không được | `Subject` phải là UUID Keycloak, không phải email |
| Traceability trống và không tạo được link | API có `POST /projects/{id}/trace/{nodes,edges}` nhưng **portal chưa có form**. Hiện chỉ tạo qua API/CLI |
| Tìm tài liệu không ra | Tìm theo từ khoá, không theo ngữ nghĩa. Thử ít từ hơn, hoặc từ đúng như trong tài liệu |
| `Phiên Keycloak cần được gia hạn` | Phiên OIDC hết hạn; bấm **Đăng nhập lại** |

## Ảnh chụp màn hình

Ảnh của các màn hình cần đăng nhập **chưa được chụp**. Repo này có quy định tại [screenshots/README.md](screenshots/README.md): ảnh chỉ được là bằng chứng runtime thật, không bao giờ dựng trạng thái đăng nhập giả. Các màn hình `/app/*` cần một phiên Keycloak thật mới chụp được, nên phần đó còn để trống thay vì minh hoạ bằng ảnh dựng.
