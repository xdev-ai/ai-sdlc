(function () {
  "use strict";

  var locale = document.documentElement.lang === "vi" ? "vi" : "en";
  var vi = {
    "Control plane online": "Hệ thống quản trị đang trực tuyến",
    "CONTROL PLANE": "HỆ THỐNG QUẢN TRỊ",
    "Overview": "Tổng quan",
    "Projects": "Dự án",
    "Spec kit registry": "Kho Spec Kit",
    "Validations": "Xác thực",
    "Evidence repository": "Kho bằng chứng",
    "Traceability": "Truy xuất nguồn gốc",
    "GOVERNANCE": "QUẢN TRỊ",
    "Policy & constitution": "Chính sách & hiến chương",
    "Review queue": "Hàng đợi phê duyệt",
    "Quality signals": "Tín hiệu chất lượng",
    "Audit ledger": "Sổ cái kiểm toán",
    "Governance active": "Quản trị đang hoạt động",
    "Menu": "Trình đơn",
    "Sign out": "Đăng xuất",
    "Language": "Ngôn ngữ",
    "Apply scope": "Áp dụng phạm vi",
    "Select organization": "Chọn tổ chức",
    "Select project": "Chọn dự án",
    "Live governed data · browser never stores API tokens": "Dữ liệu quản trị trực tiếp · trình duyệt không bao giờ lưu API token",
    "Make every delivery": "Bảo vệ mọi lần bàn giao",
    "defensible.": "có thể chứng minh.",
    "Project portfolio": "Danh mục dự án",
    "Registry & pins": "Kho đăng ký & ghim phiên bản",
    "Validation evidence": "Bằng chứng xác thực",
    "Traceability graph": "Đồ thị truy xuất nguồn gốc",
    "Human review queue": "Hàng đợi phê duyệt con người",
    "Quality counter-metrics": "Chỉ số đối trọng chất lượng",
    "Immutable audit ledger": "Sổ cái kiểm toán bất biến",
    "Evidence first.": "Bằng chứng trước.",
    "Decision second.": "Quyết định sau.",
    "Open governed portfolio": "Mở danh mục được quản trị",
    "Organizations": "Tổ chức",
    "Projects & access": "Dự án & quyền truy cập",
    "Create project": "Tạo dự án",
    "Project name": "Tên dự án",
    "Description": "Mô tả",
    "Create governed project": "Tạo dự án được quản trị",
    "No project records in scope.": "Không có dự án trong phạm vi.",
    "Project": "Dự án",
    "Status": "Trạng thái",
    "Action": "Thao tác",
    "Open →": "Mở →",
    "Project memberships": "Thành viên dự án",
    "Invite member": "Mời thành viên",
    "Subject": "Đối tượng",
    "Role": "Vai trò",
    "Invite": "Mời",
    "Joined": "Đã tham gia",
    "Administration": "Quản trị",
    "Update": "Cập nhật",
    "Remove": "Xóa",
    "← Previous": "← Trước",
    "Next →": "Tiếp →",
    "Register version": "Đăng ký phiên bản",
    "Version": "Phiên bản",
    "Layer": "Lớp",
    "Register immutable kit": "Đăng ký kit bất biến",
    "Pin": "Ghim",
    "Unpin": "Bỏ ghim",
    "Deprecate": "Ngừng dùng",
    "Select project": "Chọn dự án",
    "Validation runs": "Lần chạy xác thực",
    "All statuses": "Mọi trạng thái",
    "Filter": "Lọc",
    "Inspect": "Kiểm tra",
    "Evidence assets": "Tài sản bằng chứng",
    "Upload evidence": "Tải bằng chứng lên",
    "Upload": "Tải lên",
    "Download": "Tải xuống",
    "Retention": "Lưu giữ",
    "Apply retention": "Áp dụng lưu giữ",
    "Delete": "Xóa",
    "Traceability explorer": "Trình khám phá truy xuất nguồn gốc",
    "Policies": "Chính sách",
    "Constitutions": "Hiến chương",
    "Create policy": "Tạo chính sách",
    "Create constitution": "Tạo hiến chương",
    "Review items": "Hạng mục phê duyệt",
    "Request exception": "Yêu cầu ngoại lệ",
    "Open human review": "Mở phê duyệt con người",
    "Approve": "Phê duyệt",
    "Reject": "Từ chối",
    "Create review item": "Tạo hạng mục phê duyệt",
    "Review type": "Loại phê duyệt",
    "Decision title": "Tiêu đề quyết định",
    "Capability grants": "Cấp quyền năng lực",
    "Capability": "Năng lực",
    "Grant scoped capability": "Cấp năng lực theo phạm vi",
    "Audit integrity": "Tính toàn vẹn kiểm toán",
    "verified": "đã xác minh",
    "select org": "chọn tổ chức",
    "Platform operating principles": "Nguyên tắc vận hành nền tảng",
    "GOVERNED AI-ASSISTED DELIVERY": "BÀN GIAO CÓ QUẢN TRỊ VỚI AI",
    "Quality is not a feeling.": "Chất lượng không phải là cảm giác.",
    "It is evidence.": "Đó là bằng chứng.",
    "Open control plane": "Mở hệ thống quản trị",
    "Architecture": "Kiến trúc",
    "LIVE GOVERNANCE MODEL": "MÔ HÌNH QUẢN TRỊ TRỰC TIẾP",
    "Deterministic execution": "Thực thi tất định",
    "Human authority": "Quyền quyết định của con người",
    "Immutable evidence": "Bằng chứng bất biến",
    "FOUR PLANES, ONE STANDARD": "BỐN LỚP, MỘT TIÊU CHUẨN",
    "Execution stays fast.": "Thực thi vẫn nhanh.",
    "Governance stays firm.": "Quản trị vẫn vững chắc.",
    "Deployment frequency": "Tần suất triển khai",
    "Lead time (h)": "Thời gian chờ (giờ)",
    "Change failure rate": "Tỷ lệ thất bại thay đổi",
    "Review delta (h)": "Độ trễ phê duyệt (giờ)",
    "Rework rate": "Tỷ lệ làm lại",
    "Queue health": "Sức khỏe hàng đợi",
    "Spec alignment": "Mức khớp đặc tả"
    ,"No quality periods are available yet.": "Chưa có kỳ chất lượng nào."
    ,"Only persisted metric snapshots are charted; the server-rendered workspace remains the source of truth.": "Chỉ các ảnh chụp chỉ số đã lưu mới được biểu diễn; không gian làm việc do server render vẫn là nguồn dữ liệu chuẩn."
    ,"Explore metric": "Khám phá chỉ số"
    ,"recorded periods": "kỳ đã ghi nhận"
    ,"trend": "xu hướng"
    ,"Chart module unavailable. Review the server-rendered table below.": "Không có mô-đun biểu đồ. Hãy xem bảng do server render bên dưới."
    ,"Traceability graph. Use arrow keys to select nodes.": "Đồ thị truy xuất nguồn gốc. Dùng các phím mũi tên để chọn nút."
    ,"Select a node to inspect its governed delivery detail.": "Chọn một nút để xem chi tiết bàn giao được quản trị."
    ,"Filter live validation evidence": "Lọc bằng chứng xác thực trực tiếp"
    ,"Status, model pin, kit or key": "Trạng thái, model pin, kit hoặc khóa"
    ,"of": "trên"
    ,"runs match": "lần chạy khớp"
    ,"require attention. Select a server-rendered run for the immutable evidence record.": "cần được chú ý. Chọn lần chạy do server render để xem hồ sơ bằng chứng bất biến."
    ,"Decision was not accepted. The server-rendered review form below remains available.": "Quyết định không được chấp nhận. Biểu mẫu phê duyệt do server render bên dưới vẫn khả dụng."
    ,"Network error. Use the server-rendered review form below.": "Lỗi mạng. Hãy dùng biểu mẫu phê duyệt do server render bên dưới."
    ,"pending review decisions": "quyết định phê duyệt đang chờ"
    ,"pending exceptions": "ngoại lệ đang chờ"
    ,"Every decision remains server-authorized, CSRF-protected and appended to the immutable audit ledger. Exception approvals also require an explicit UTC expiry.": "Mọi quyết định đều do server ủy quyền, được bảo vệ CSRF và được ghi thêm vào sổ cái kiểm toán bất biến. Phê duyệt ngoại lệ cũng yêu cầu thời hạn UTC rõ ràng."
    ,"Keycloak session connected": "Phiên Keycloak đã kết nối"
    ,"Keycloak session needs renewal": "Phiên Keycloak cần được gia hạn"
    ,"SECURE SESSION": "PHIÊN BẢO MẬT"
    ,"Reconnect to continue your": "Kết nối lại để tiếp tục"
    ,"governed work.": "công việc được quản trị."
    ,"Your secure sign-in session ended or could not be refreshed. No draft, evidence, API token, or identity-provider detail is displayed on this page.": "Phiên đăng nhập bảo mật đã kết thúc hoặc không thể làm mới. Trang này không hiển thị bản nháp, bằng chứng, API token hoặc chi tiết nhà cung cấp danh tính."
    ,"Sign in again": "Đăng nhập lại"
    ,"Return to home": "Về trang chủ"
    ,"After a successful sign-in, the portal resumes a safe saved workspace request when one is available; otherwise it opens the standard overview.": "Sau khi đăng nhập thành công, portal sẽ tiếp tục yêu cầu không gian làm việc đã lưu và an toàn khi có thể; nếu không, portal sẽ mở trang tổng quan tiêu chuẩn."
  };

  function translateText(root) {
    if (locale !== "vi") return;
    var walker = document.createTreeWalker(root || document.body, NodeFilter.SHOW_TEXT);
    var nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    nodes.forEach(function (node) {
      if (!node.parentElement || node.parentElement.closest("script, style, code, pre, [data-no-i18n]")) return;
      var source = node.nodeValue;
      var trimmed = source.trim();
      if (trimmed && Object.prototype.hasOwnProperty.call(vi, trimmed)) {
        node.nodeValue = source.replace(trimmed, vi[trimmed]);
      }
    });
    ["placeholder", "aria-label", "title"].forEach(function (attribute) {
      root.querySelectorAll ? root.querySelectorAll("[" + attribute + "]").forEach(function (element) {
        var value = element.getAttribute(attribute);
        if (Object.prototype.hasOwnProperty.call(vi, value)) element.setAttribute(attribute, vi[value]);
      }) : null;
    });
  }

  function navigateLocale(event) {
    var link = event.target.closest("[data-locale-choice]");
    if (!link) return;
    event.preventDefault();
    var targetLocale = link.getAttribute("data-locale-choice");
    var target = new URL(window.location.href);
    target.searchParams.set("lang", targetLocale);
    window.location.assign(target.toString());
  }

  window.AISDLC_I18N = { locale: locale, t: function (key) { return locale === "vi" && vi[key] ? vi[key] : key; }, translate: translateText };
  document.addEventListener("click", navigateLocale);
  document.addEventListener("DOMContentLoaded", function () {
    translateText(document);
    if (locale === "vi" && document.body) {
      new MutationObserver(function (records) {
        records.forEach(function (record) {
          record.addedNodes.forEach(function (node) {
            if (node.nodeType === Node.ELEMENT_NODE) translateText(node);
            if (node.nodeType === Node.TEXT_NODE && node.parentElement) translateText(node.parentElement);
          });
        });
      }).observe(document.body, { childList: true, subtree: true });
    }
  });
}());
