// WMS QR Labels: print the QR labels of what a warehouse holds on a label printer.
//   * every item / batch in stock in the warehouse (one label each, printed in one go),
//   * one item, or
//   * one batch.
// The PDF has one label per page, the page exactly the label size, so a thermal / roll printer
// feeds one label per page. The QR carries {"item_code": ..., "batch_no": ...} with the keys of
// WMS Settings: exactly what the WMS app scans. Opened from the awesome bar ("WMS QR Labels") or
// from Warehouse > Print QR Labels (which passes the warehouse).
const WMS_LABELS_API = "wmserp_picking.api.labels.";
const WMS_LABEL_MODES = {
	[__("All items in stock")]: "warehouse",
	[__("One item")]: "item",
	[__("One batch")]: "batch",
};

frappe.pages["wms-qr-labels"].on_page_load = function (wrapper) {
	const page = frappe.ui.make_app_page({ parent: wrapper, title: __("WMS QR Labels"), single_column: true });
	wrapper.wms_labels = new WmsQrLabels(page);
};

frappe.pages["wms-qr-labels"].on_page_show = function (wrapper) {
	const options = frappe.route_options || {};
	frappe.route_options = null;
	if (wrapper.wms_labels) wrapper.wms_labels.apply_route_options(options);
};

class WmsQrLabels {
	constructor(page) {
		this.page = page;
		this.page.set_primary_action(__("Print Labels"), () => this.print(), "printer");
		this.page.set_secondary_action(__("Preview"), () => this.preview());
		this.make_form();
		this.$summary = $('<div class="wms-labels-summary" style="margin: 12px 0;"></div>').appendTo(this.page.body);
		this.$preview = $('<div class="wms-labels-preview"></div>').appendTo(this.page.body);
		this.load_formats();
	}

	make_form() {
		const me = this;
		const mode_label = (mode) => Object.keys(WMS_LABEL_MODES).find((key) => WMS_LABEL_MODES[key] === mode);
		this.form = new frappe.ui.FieldGroup({
			body: this.page.body,
			fields: [
				{ fieldname: "warehouse", label: __("Warehouse"), fieldtype: "Link", options: "Warehouse", reqd: 1, onchange: () => me.changed() },
				{
					fieldname: "mode",
					label: __("Print"),
					fieldtype: "Select",
					options: Object.keys(WMS_LABEL_MODES).join("\n"),
					default: mode_label("warehouse"),
					reqd: 1,
					onchange: () => me.changed(),
				},
				{
					fieldname: "item_code",
					label: __("Item"),
					fieldtype: "Link",
					options: "Item",
					depends_on: `eval:doc.mode==="${mode_label("item")}" || doc.mode==="${mode_label("batch")}"`,
					mandatory_depends_on: `eval:doc.mode==="${mode_label("item")}"`,
					description: __("Items with stock in the warehouse."),
					get_query: () => ({ query: WMS_LABELS_API + "stocked_item_query", filters: { warehouse: me.value("warehouse") } }),
					onchange: () => me.changed(),
				},
				{
					fieldname: "batch_no",
					label: __("Batch"),
					fieldtype: "Link",
					options: "Batch",
					depends_on: `eval:doc.mode==="${mode_label("batch")}"`,
					mandatory_depends_on: `eval:doc.mode==="${mode_label("batch")}"`,
					description: __("Batches with stock in the warehouse (of the item, when one is chosen)."),
					get_query: () => ({
						query: WMS_LABELS_API + "stocked_batch_query",
						filters: { warehouse: me.value("warehouse"), item_code: me.value("item_code") || "" },
					}),
					onchange: () => me.changed(),
				},
				{
					fieldname: "per_batch",
					label: __("One label per batch for batch tracked items"),
					fieldtype: "Check",
					default: 1,
					depends_on: `eval:doc.mode==="${mode_label("warehouse")}"`,
					description: __("Off: one item label (item code only) per item."),
					onchange: () => me.changed(),
				},
				{ fieldtype: "Column Break" },
				{ fieldname: "label_width", label: __("Label Width (mm)"), fieldtype: "Float", default: 50, reqd: 1, onchange: () => me.changed() },
				{ fieldname: "label_height", label: __("Label Height (mm)"), fieldtype: "Float", default: 30, reqd: 1, onchange: () => me.changed() },
				{ fieldname: "copies", label: __("Copies of each label"), fieldtype: "Int", default: 1, reqd: 1, onchange: () => me.changed() },
				{ fieldname: "print_format", label: __("Label Format"), fieldtype: "Select", options: "WMS Warehouse QR Labels", default: "WMS Warehouse QR Labels" },
			],
		});
		this.form.make();
	}

	value(fieldname) {
		return this.form.get_value(fieldname);
	}

	apply_route_options(options) {
		["warehouse", "item_code", "batch_no"].forEach((key) => {
			if (options[key]) this.form.set_value(key, options[key]);
		});
		if (options.mode && Object.values(WMS_LABEL_MODES).includes(options.mode)) {
			this.form.set_value("mode", Object.keys(WMS_LABEL_MODES).find((key) => WMS_LABEL_MODES[key] === options.mode));
		}
	}

	load_formats() {
		frappe.call({ method: WMS_LABELS_API + "get_roll_formats" }).then((r) => {
			const names = r.message || [];
			if (!names.length) return;
			const field = this.form.get_field("print_format");
			field.df.options = names.join("\n");
			field.refresh();
			if (!names.includes(this.value("print_format"))) this.form.set_value("print_format", names[0]);
		});
	}

	args() {
		const values = this.form.get_values();
		if (!values) return null;
		return {
			warehouse: values.warehouse,
			mode: WMS_LABEL_MODES[values.mode] || "warehouse",
			item_code: values.item_code || "",
			batch_no: values.batch_no || "",
			per_batch: values.per_batch ? 1 : 0,
			copies: values.copies || 1,
			label_width: values.label_width,
			label_height: values.label_height,
			print_format: values.print_format || "",
		};
	}

	/** True when the selection is complete enough to preview without asking anything. */
	ready() {
		const mode = WMS_LABEL_MODES[this.value("mode")];
		if (!this.value("warehouse")) return false;
		if (mode === "item") return !!this.value("item_code");
		if (mode === "batch") return !!this.value("batch_no");
		return true;
	}

	changed() {
		this.$summary.empty();
		this.$preview.empty();
		clearTimeout(this.preview_timer);
		if (this.ready()) this.preview_timer = setTimeout(() => this.preview(true), 400);
	}

	preview(quiet) {
		const args = this.args();
		if (!args) return;
		frappe.call({ method: WMS_LABELS_API + "warehouse_label_preview", args, freeze: !quiet }).then((r) => {
			if (r && r.message) this.show_preview(r.message);
		});
	}

	show_preview(data) {
		const summary = __("{0} labels to print ({1} items, {2} batches) on {3} x {4} mm labels.", [
			data.count,
			data.items,
			data.batches,
			data.label_width,
			data.label_height,
		]);
		const rows = (data.rows || [])
			.map(
				(row) => `<tr><td>${frappe.utils.escape_html(row.item_code)}</td><td>${frappe.utils.escape_html(row.item_name || "")}</td>
				<td>${frappe.utils.escape_html(row.batch_no || "")}</td><td>${row.expiry_date ? frappe.datetime.str_to_user(row.expiry_date) : ""}</td>
				<td class="text-right">${format_number(row.qty)} ${frappe.utils.escape_html(row.uom || "")}</td></tr>`
			)
			.join("");
		this.$summary.html(`<div class="alert alert-info">${summary}</div>`);
		const more = data.labels > (data.rows || []).length ? `<p class="text-muted small">${__("First {0} shown.", [(data.rows || []).length])}</p>` : "";
		this.$preview.html(`
			<div class="row">
				<div class="col-md-7">
					<table class="table table-bordered table-condensed small">
						<thead><tr><th>${__("Item")}</th><th>${__("Item Name")}</th><th>${__("Batch")}</th><th>${__("Expiry")}</th><th class="text-right">${__("In stock")}</th></tr></thead>
						<tbody>${rows}</tbody>
					</table>${more}
				</div>
				<div class="col-md-5">
					<p class="text-muted small">${__("First labels as they print:")}</p>
					<iframe class="wms-label-frame" style="width: 100%; height: 420px; border: 1px solid var(--border-color); background: #fff;"></iframe>
				</div>
			</div>`);
		const frame = this.$preview.find("iframe").get(0);
		const preview_css = "<style>table.wms-label { border: 1px dashed #999; margin: 0 auto 4mm auto; }</style>";
		frame.srcdoc = (data.html || "").replace("</head>", preview_css + "</head>");
	}

	print() {
		const args = this.args();
		if (!args) return;
		const query = Object.keys(args)
			.map((key) => encodeURIComponent(key) + "=" + encodeURIComponent(args[key]))
			.join("&");
		const w = window.open("/api/method/" + WMS_LABELS_API + "download_warehouse_labels?" + query);
		if (!w) frappe.msgprint(__("Please enable pop-ups"));
	}
}
