export interface Goods {
  id: number
  goodsname: string
  providerid: number
  categoryid: number
  productcode: string
  barcode?: string
  promitcode: string
  description: string
  size: string
  goodspackage: string
  price: number
  costPrice?: number | null
  number: number
  dangernum: number
  goodsimg: string
  available: number
  attribute: string
  providername?: string
  categoryname?: string
}
