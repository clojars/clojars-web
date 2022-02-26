const configureToggleTokenRows = (toggleSel, rowSel) => {
$(toggleSel).change(() => {
  if ($(toggleSel).is(':checked')) {
    $(rowSel).show();
  } else {
    $(rowSel).hide();
  }
});
};

const hideTokenRows = (sel) => $(sel).hide();

$(() => {
  hideTokenRows('.token-disabled');
  hideTokenRows('.token-used');
  configureToggleTokenRows('#show-disabled', '.token-disabled');
  configureToggleTokenRows('#show-used', '.token-used');
});
